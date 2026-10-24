"""Bounded parsing for private MinIO documents; no filesystem extraction or external links."""
import hashlib
import asyncio
import io
import os
import zipfile
import re
from dataclasses import dataclass
from urllib.parse import urlparse

import httpx
from llama_index.core.node_parser import SentenceSplitter
from .models import BookFile, BookIndexRequest

MAX_BYTES = 20 * 1024 * 1024
MAX_TEXT = 2_000_000


class DocumentError(ValueError):
    pass


@dataclass
class Section:
    text: str
    page: int | None = None


def parse_document(data: bytes, extension: str) -> list[Section]:
    if len(data) > MAX_BYTES:
        raise DocumentError("资料文件超过 20MB")
    if extension in ("txt", "md"):
        if b"\0" in data:
            raise DocumentError("文本文件包含二进制内容")
        text = None
        for encoding in ("utf-8-sig", "gb18030"):
            try:
                text = data.decode(encoding)
                break
            except UnicodeDecodeError:
                continue
        if text is None:
            raise DocumentError("文本编码不受支持，请转换为 UTF-8")
        sections = [Section(text)]
    elif extension == "pdf":
        from pypdf import PdfReader
        try:
            reader = PdfReader(io.BytesIO(data), strict=False)
            if reader.is_encrypted:
                raise DocumentError("PDF 已加密，请上传解密后的文件")
            if len(reader.pages) > 1000:
                raise DocumentError("PDF 超过 1000 页，请分卷上传")
            sections = [Section(page.extract_text() or "", number + 1) for number, page in enumerate(reader.pages)]
        except DocumentError:
            raise
        except Exception:
            raise DocumentError("PDF 无法解析，请检查文件是否损坏") from None
    elif extension == "docx":
        from docx import Document
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                entries = archive.infolist()
                if len(entries) > 3000 or sum(entry.file_size for entry in entries) > 60 * 1024 * 1024:
                    raise DocumentError("DOCX 解压后过大，无法安全处理")
                if "word/document.xml" not in archive.namelist():
                    raise DocumentError("文件不是有效 DOCX")
            document = Document(io.BytesIO(data))
            blocks = []
            for block in document.iter_inner_content():
                if hasattr(block, "text"):
                    blocks.append(block.text)
                else:
                    blocks.extend(" | ".join(cell.text for cell in row.cells) for row in block.rows)
            sections = [Section("\n\n".join(blocks))]
        except DocumentError:
            raise
        except Exception:
            raise DocumentError("DOCX 无法解析，请检查文件是否损坏") from None
    else:
        raise DocumentError("不支持的资料格式")
    sections = [Section(section.text.strip(), section.page) for section in sections if section.text.strip()]
    if not sections:
        raise DocumentError("没有提取到文字；扫描版 PDF 请先完成 OCR，再上传文本型 PDF")
    if sum(len(section.text) for section in sections) > MAX_TEXT:
        raise DocumentError("解析文字超过 200 万字符，请拆分资料")
    return sections


async def download_document(file: BookFile, transport=None) -> bytes:
    # Only the configured MinIO origin can be fetched; signed URLs are never logged.
    allowed = urlparse(os.getenv("MINIO_ENDPOINT", "http://127.0.0.1:9000"))
    target = urlparse(file.url)
    if (target.scheme, target.netloc) != (allowed.scheme, allowed.netloc) or target.username or target.password:
        raise DocumentError("资料下载地址不属于配置的 MinIO 服务")
    async with httpx.AsyncClient(timeout=60, follow_redirects=False, transport=transport) as client:
        async with client.stream("GET", file.url) as response:
            if response.status_code != 200:
                raise DocumentError("资料下载失败，链接可能过期，请重试索引")
            parts, size = [], 0
            async for part in response.aiter_bytes():
                size += len(part)
                if size > MAX_BYTES:
                    raise DocumentError("资料下载内容超过 20MB")
                parts.append(part)
    data = b"".join(parts)
    if len(data) != file.size or hashlib.sha256(data).hexdigest() != file.sha256:
        raise DocumentError("资料大小或校验值不一致，请重新上传")
    return data


def split_sections(book_id: int, title: str, source_key: str, source_name: str, file_id: int | None, sections: list[Section]) -> list[dict]:
    # Preserve punctuation and whitespace without loading NLTK corpus resources.
    # LlamaIndex still enforces token size, overlap and its fallback splitting.
    splitter = SentenceSplitter(chunk_size=400, chunk_overlap=64,
        chunking_tokenizer_fn=lambda text: re.findall(r".+?(?:[。！？]|\n|[.!?](?=\s|$)|$)", text, re.S))
    nodes = []
    for section in sections:
        for text in splitter.split_text(section.text):
            number = len(nodes) + 1
            embed_text = title[:80] + "\n" + source_name[:50] + "\n" + text
            identity = f"{book_id}:{source_key}:{number}:" + embed_text
            nodes.append({"chunk_id": hashlib.sha256(identity.encode()).hexdigest(), "book_id": book_id,
                "source_key": source_key, "source_name": source_name, "file_id": file_id,
                "chunk_no": number, "page_no": section.page, "text": text, "embed_text": embed_text})
    return nodes


async def build_nodes(request: BookIndexRequest, transport=None) -> list[dict]:
    nodes = split_sections(request.book_id, request.title, "body", "Book 正文", None, [Section(request.content)]) if request.content.strip() else []
    for file in request.files:
        try:
            data = await download_document(file, transport)
            sections = await asyncio.to_thread(parse_document, data, file.extension)
        except httpx.HTTPError:
            raise DocumentError(f"{file.name}：无法连接 MinIO 下载资料，请重试") from None
        except DocumentError as error:
            raise DocumentError(f"{file.name}：{error}") from None
        nodes.extend(split_sections(request.book_id, request.title, "file:" + str(file.id), file.name, file.id, sections))
    if len(nodes) > 10000:
        raise DocumentError("Book 分块超过 10000，请拆分为多个 Book")
    return nodes


async def read_book(request: BookIndexRequest, transport=None) -> dict:
    """Read original text rather than concatenating overlapping vector chunks."""
    sections = []
    for file in request.files:
        try:
            data = await download_document(file, transport)
            parsed = await asyncio.to_thread(parse_document, data, file.extension)
        except httpx.HTTPError:
            raise DocumentError("无法下载 Book 原文件，请重试") from None
        for section in parsed:
            sections.append({"text": section.text, "page": section.page, "fileId": file.id,
                             "sourceName": file.name, "format": file.extension})
    return {"sections": sections}

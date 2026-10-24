"""Paragraph embeddings choose semantic boundaries; token limits remain hard bounds."""
import hashlib
import re
import numpy as np
from llama_index.core.node_parser import SentenceSplitter
from .documents import Section, read_book, split_sections, DocumentError


async def semantic_nodes(request, provider):
    sources = [("body", "Book 正文", None, Section(request.content))] if request.content.strip() else []
    original = await read_book(request)
    for section in original["sections"]:
        sources.append(("file:" + str(section["fileId"]), section["sourceName"], section["fileId"], Section(section["text"], section["page"])))
    splitter = SentenceSplitter(chunk_size=180, chunk_overlap=0,chunking_tokenizer_fn=lambda text: re.findall(r".+?(?:[。！？]|\n|[.!?](?=\s|$)|$)", text, re.S))
    units = [(key, name, file_id, section.page, text) for key, name, file_id, section in sources for text in splitter.split_text(section.text)]
    if len(units) > 10000:
        raise DocumentError("语义切片超过 10000 个段落，请拆分 Book")
    if not units: return []
    embeddings = await provider.embeddings([request.title[:80] + "\n" + unit[4] for unit in units])
    if len(embeddings) != len(units): raise DocumentError("语义边界向量数量不匹配")
    vectors = [np.asarray(vector, dtype=np.float32) for vector in embeddings]
    # Hard cap on combined units avoids unbounded topic clusters.
    groups, current = [], [units[0]]
    for index in range(1, len(units)):
        left, right = vectors[index-1], vectors[index]
        similarity = float(np.dot(left, right) / max(1e-9, np.linalg.norm(left)*np.linalg.norm(right)))
        if units[index][:4] != units[index-1][:4] or similarity < .76 or len(current) >= 2:
            groups.append(current); current = []
        current.append(units[index])
    groups.append(current)
    result, counters = [], {}
    for group in groups:
        key, name, file_id, page, _ = group[0]
        for node in split_sections(request.book_id, request.title, key, name, file_id, [Section("\n\n".join(unit[4] for unit in group), page)]):
            counters[key] = counters.get(key, 0) + 1
            node["chunk_no"] = counters[key]
            node["chunk_id"] = hashlib.sha256(f"{request.book_id}:{key}:semantic-v1:{node['chunk_no']}:{node['embed_text']}".encode()).hexdigest()
            result.append(node)
    if len(result) > 10000: raise DocumentError("语义分块过多，请拆分 Book")
    return result

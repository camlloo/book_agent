"""Transform complete documents in bounded pieces instead of silently discarding tails."""
import re
from .context import excerpt, passages, tokens
from .provider import ModelUnavailable


def document_parts(text: str, budget=3200) -> list[str]:
    # A fenced code block is indivisible; ordinary paragraphs may split at token bounds.
    blocks, pending, fence = [], "", None
    for line in text.splitlines(keepends=True):
        if fence is None:
            opening = re.match(r"^ {0,3}(`{3,}|~{3,})", line)
            if opening:
                if pending: blocks.append((False, pending))
                pending = line; fence = opening.group(1)
            else:
                pending += line
        else:
            pending += line
            if re.fullmatch(r" {0,3}" + re.escape(fence[0]) + "{" + str(len(fence)) + r",}[ \t]*(?:\r?\n)?", line):
                blocks.append((True, pending)); pending = ""; fence = None
    if pending: blocks.append((fence is not None, pending))
    units = []
    for is_code, block in blocks:
        if not block:
            continue
        if is_code:
            if tokens(block) > budget:
                raise ModelUnavailable("单个代码块超过上下文预算，请先按函数或文件拆分代码块。")
            units.append(block)
        else:
            for paragraph in re.split(r"(\n\s*\n)", block):
                if tokens(paragraph) <= budget:
                    units.append(paragraph)
                else:
                    units.extend(passages(paragraph, budget - 10))
    parts, current = [], ""
    for unit in units:
        if current and tokens(current + unit) > budget:
            parts.append(current); current = ""
        current += unit
    if current:
        parts.append(current)
    if len(parts) > 64:
        raise ModelUnavailable("全文需要处理超过64个片段，请按章节分次处理。")
    return parts or [""]


async def rewrite(provider, task, system, prefix, content, decode=None):
    parts = document_parts(content) if tokens(content) > 7000 else [content]
    outline = "\n".join(re.findall(r"^#{1,6} .+$", content, re.M))
    structure = excerpt(outline, 1000) if len(parts) > 1 else ""
    outputs, findings = [], []
    for index, part in enumerate(parts):
        instruction = prefix
        if len(parts) > 1:
            instruction += f"\n全文目录（只供衔接）：\n{structure}\n这是第{index+1}/{len(parts)}个连续片段。只改写当前片段，保留其中事实、结构与代码，不重写全书，不添加总标题、开场或总结。"
            if outputs:
                instruction += "\n前一片段结尾（只供衔接，不要复述）：\n" + excerpt(outputs[-1][-1500:], 400)
        raw = await provider.complete(task, system, instruction + "\n当前待处理原文：\n" + part)
        if decode:
            value = decode(raw); output = value.get("content", "")
            if not isinstance(output, str) or not output.strip():
                raise ModelUnavailable("AI 未返回完整的片段正文")
            details = value.get("findings", [])
            if isinstance(details, list):
                findings.extend(str(item)[:500] for item in details[:12])
        else:
            output = raw
        outputs.append(output.strip())
    combined = "\n\n".join(outputs)
    if len(combined) > 1_000_000:
        raise ModelUnavailable("生成正文超过Book大小限制，请按章节处理。")
    return combined, list(dict.fromkeys(findings))[:24], {"documentParts": len(parts), "contentTokens": tokens(content), "contentSampled": False}

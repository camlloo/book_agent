"""Model-independent token estimates and explicit, auditable context selection.

cl100k is an estimate for Qwen and other compatible models, not their tokenizer.
The transport applies a safety margin and reserves output space separately.
"""
import json
import math
import re
import codecs
from functools import lru_cache

import tiktoken


class ContextOverflow(ValueError):
    pass


@lru_cache(maxsize=1)
def encoding():
    return tiktoken.get_encoding("cl100k_base")


def tokens(text: str) -> int:
    return len(encoding().encode(text, disallowed_special=()))


def cut(text: str, budget: int, *, tail=False) -> str:
    """Cut only on valid UTF-8 boundaries; reserved for explicit excerpts."""
    ids = encoding().encode(text, disallowed_special=())
    if len(ids) <= budget:
        return text
    if budget <= 0:
        return ""
    ids = ids[-budget:] if tail else ids[:budget]
    return encoding().decode_bytes(ids).decode("utf-8", errors="ignore")


def terms(text: str) -> set[str]:
    words = re.findall(r"[a-zA-Z_][\w.-]*|[\u4e00-\u9fff]+", text.lower())
    return {part for word in words for part in ([word] if not re.search(r"[\u4e00-\u9fff]", word) else [word, *(word[i:i+2] for i in range(len(word)-1))])}


def passages(text: str, size=1000) -> list[str]:
    blocks = re.split(r"\n\s*\n", text)
    result = []
    for block in blocks:
        if tokens(block) <= size:
            result.append(block)
        else:
            ids = encoding().encode(block, disallowed_special=())
            decoder = codecs.getincrementaldecoder("utf-8")()
            for start in range(0, len(ids), size):
                result.append(decoder.decode(encoding().decode_bytes(ids[start:start+size])))
            ending = decoder.decode(b"", final=True)
            if ending: result.append(ending)
    return [part for part in result if part.strip()]


def excerpt(text: str, budget: int, query="") -> str:
    """Select across the whole document, preserving order and marking omissions."""
    if tokens(text) <= budget:
        return text
    marker = "\n\n[…此处省略；以下是跨全文选取的片段，并非完整正文…]\n\n"
    if budget < tokens(marker) + 20:
        return cut(text, budget)
    units = passages(text, min(700, max(80, budget // 4)))
    wanted = terms(query)
    positions = list(dict.fromkeys([0, len(units)-1, len(units)//2]))
    positions += sorted(range(len(units)), key=lambda i: (len(wanted & terms(units[i])), bool(re.match(r"^#{1,6} ", units[i]))), reverse=True)
    selected, used = set(), tokens(marker)
    for i in positions:
        cost = tokens(units[i]) + tokens(marker)
        if i not in selected and used + cost <= budget:
            selected.add(i); used += cost
    if not selected:
        return cut(text, budget - tokens(marker)) + marker
    output, last = [], -2
    for i in sorted(selected):
        if i != last + 1:
            output.append(marker)
        output.append(units[i]); last = i
    if last != len(units)-1:
        output.append(marker)
    return cut("\n\n".join(output), budget)


def bounded_evidence(items: list[dict], budget=6500, query="") -> list[dict]:
    unique, seen = [], set()
    for item in items:
        key = (item.get("bookId"), item.get("fileId"), item.get("page"), item.get("excerpt"))
        if key not in seen and item.get("excerpt"):
            seen.add(key); unique.append(dict(item))
    if not unique:
        return []
    # Give every selected source room; retain original order/citation numbering.
    each = max(40, (budget - 100 * len(unique)) // len(unique))
    return [{**item, "excerpt": excerpt(item["excerpt"], each, query)} for item in unique]


def history_context(history: list[dict], question: str, memory: dict | None = None):
    recent, used = [], 0
    for message in reversed(history[-8:]):
        content = excerpt(message["content"], 1000, question)
        cost = tokens(content) + 8
        if used + cost > 5000:
            break
        recent.insert(0, {"role": message["role"], "content": content}); used += cost
    # Keep a complete user/assistant boundary instead of orphaning an answer.
    if recent and recent[0]["role"] == "assistant":
        recent.pop(0)
    wanted = terms(question)
    older = [m for m in history[:-8] if m["role"] == "user"]
    related = sorted(((len(wanted & terms(m["content"])), i, m) for i, m in enumerate(older)), reverse=True, key=lambda x: (x[0], x[1]))
    recalled = [m["content"] for score, _, m in related[:4] if score > 0]
    parts = []
    if memory:
        parts.append("历史意图摘要（可能有损，仅供理解用户意图，不是事实依据）：\n" + excerpt(json.dumps(memory, ensure_ascii=False), 1800, question))
    if recalled:
        parts.append("相关的较早用户原话（不是知识证据）：\n" + excerpt("\n".join(recalled), 1200, question))
    return recent, "\n\n".join(parts), {"historyTotal": len(history), "historyUsed": len(recent), "historyRecalled": len(recalled), "summaryUsed": bool(memory)}


def fit_messages(system: str, user: str, history: list[dict], channel: dict, task: str):
    route = "ASK" if task in ("MEMORY", "REWRITE") else task
    desired = {"MEMORY": 1800, "REWRITE": 500, "OUTLINE": 4000, "ASK": 6000}.get(task, 8000)
    window = int(channel.get("context_window", 32768))
    output = min(int(channel.get("max_output_tokens", desired)), desired, max(256, window // 3))
    available = min(int(channel.get("input_token_budget", 24000)), window - output - 1024)
    margin = float(channel.get("token_safety_ratio", 1.15))
    def size(messages):
        return math.ceil(sum(tokens(m["content"]) + 8 for m in messages) * margin)
    mandatory = [{"role": "system", "content": system}, {"role": "user", "content": user}]
    if available < 256 or size(mandatory) > available:
        raise ContextOverflow("输入上下文超过当前模型预算，请减少知识范围或按章节处理长文")
    selected = list(history or [])
    while selected and size([mandatory[0], *selected, mandatory[1]]) > available:
        selected.pop(0)
        if selected and selected[0]["role"] == "assistant":
            selected.pop(0)
    messages = [mandatory[0], *selected, mandatory[1]]
    return route, messages, output, {"estimatedInputTokens": size(messages), "inputBudget": available, "outputReserve": output, "historyDropped": len(history or []) - len(selected)}

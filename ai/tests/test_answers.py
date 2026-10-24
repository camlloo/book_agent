import json
import pytest
from app.answers import empty_answer, grounded_answer, parse_answer
from app.models import BookAskRequest
from app.provider import ModelUnavailable
from app import book_rag


class FixtureProvider:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.calls = []

    async def complete(self, task, system, user, history=None):
        self.calls.append((task, system, user, history))
        return next(self.responses)


def test_answer_contract_and_code_indexing():
    answer = parse_answer(json.dumps({"conclusion": "使用 `values[99]` 读取元素。", "evidence": "原文说明了索引规则。[1]"}), 1)
    assert answer.startswith("**结论：**")
    assert "**论据：**" in answer
    assert "`values[99]`" in answer


@pytest.mark.parametrize("data", [
    {"conclusion": "结论", "evidence": "无来源"},
    {"conclusion": "结论", "evidence": "来源越界[2]"},
    {"conclusion": "结论", "evidence": "非法来源[0]"},
    {"conclusion": "", "evidence": "来源[1]"},
    {"conclusion": "结论", "evidence": ["来源[1]"]},
    {"conclusion": "结论", "evidence": "来源[1]", "extra": "多余段落"},
])
def test_unverifiable_answers_rejected(data):
    with pytest.raises(ValueError): parse_answer(json.dumps(data), 1)


@pytest.mark.asyncio
async def test_invalid_format_retries_without_fabricating_sources():
    provider = FixtureProvider(["随意格式", json.dumps({"conclusion": "无法确定公路等级数量。", "evidence": "本轮片段讲的是 Python，没有公路等级信息。[1]"})])
    history = [{"role": "user", "content": "此前问题"}]
    answer = await grounded_answer(provider, "公路有几级？", "[1] Python 教程", 1, "当前 Book", history)
    assert "无法确定" in answer and "[1]" in answer
    assert len(provider.calls) == 2
    assert provider.calls[0][3] == history
    assert "本轮实际返回 1 个片段" in provider.calls[0][2]
    assert "公路等级" not in provider.calls[0][1]  # Examples cannot leak invented facts into instructions.


@pytest.mark.asyncio
async def test_repeated_invalid_citation_reports_failure():
    invalid = json.dumps({"conclusion": "回答", "evidence": "伪造来源[9]"})
    with pytest.raises(ModelUnavailable):
        await grounded_answer(FixtureProvider([invalid, invalid]), "问题", "[1] 真实来源", 1, "当前 Book")


@pytest.mark.asyncio
async def test_empty_book_does_not_call_model(monkeypatch):
    class EmptyStore:
        def __init__(self, provider): pass
        async def search(self, request): return []
    monkeypatch.setattr(book_rag, "BookVectorStore", EmptyStore)
    provider = FixtureProvider([])
    answer = await book_rag.answer_book(BookAskRequest(book_id=1, revision=1, question="公路有几级？"), provider)
    assert answer["answer"] == empty_answer("当前 Book 的当前就绪版本")
    assert answer["sources"] == [] and provider.calls == []
    assert "未检索到相关出处。" in answer["answer"]


def test_evidence_is_only_verified_unique_origins():
    answer = parse_answer(json.dumps({"conclusion": "金额为 21.50 元。",
        "evidence": "冗长的解释、重复摘录和模型推理。[2] [1] [2]"}), 2)
    assert answer.split("**论据：**\n")[1] == "出处：[2]、[1]。"
    assert "冗长" not in answer


def test_same_file_page_is_not_repeated_but_other_pages_remain():
    raw = json.dumps({"conclusion": "回答", "evidence": "[1] [2] [3]"})
    answer = parse_answer(raw, 3, [(8, "资料.pdf", 4), (8, "资料.pdf", 4), (8, "资料.pdf", 5)])
    assert answer.endswith("出处：[1]、[3]。")

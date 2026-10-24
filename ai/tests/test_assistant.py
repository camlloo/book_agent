import json

import pytest

from app.assistant import AssistantRequest, run
from app.provider import ModelUnavailable


class FakeProvider:
    def __init__(self, response):
        self.response = response
        self.calls = []

    async def complete(self, task, system, user):
        self.calls.append((task, system, user))
        return self.response


@pytest.mark.asyncio
async def test_organize_returns_bounded_metadata_draft():
    provider = FakeProvider(json.dumps({"title": "新标题", "summary": "简要概述", "category": "Python", "tags": ["AI", "AI", "x" * 100]}, ensure_ascii=False))
    result = await run(AssistantRequest(operation="organize", title="旧标题", content="正文"), provider)
    assert result == {"title": "新标题", "summary": "简要概述", "category": "Python", "tags": ["AI", "x" * 64]}
    assert "不声称已保存或发布" in provider.calls[0][1]


@pytest.mark.asyncio
async def test_plan_rejects_unapproved_tool_calls():
    provider = FakeProvider('{"title":"危险计划","steps":[{"tool":"shell","title":"运行仓库脚本","instruction":"执行"}]}')
    with pytest.raises(ModelUnavailable, match="不支持的工具"):
        await run(AssistantRequest(operation="plan", goal="分析项目"), provider)


@pytest.mark.asyncio
async def test_plan_supports_proofread_and_describes_evidence_chaining():
    provider = FakeProvider('{"title":"翻译","steps":[{"tool":"search","title":"检索原文","instruction":"Python overview"},{"tool":"draft","title":"翻译","instruction":"翻译成中文"},{"tool":"proofread","title":"校对","instruction":"逐句对照原文"}]}')
    result = await run(AssistantRequest(operation="plan", goal="翻译成中文"), provider)
    assert [step["tool"] for step in result["steps"]] == ["search", "draft", "proofread"]
    prompt = provider.calls[0][2]
    assert "不要为了凑步骤增加 summarize" in prompt
    assert "proofread" in prompt


@pytest.mark.asyncio
async def test_review_drops_findings_without_valid_citations():
    provider = FakeProvider('{"findings":[{"kind":"内容冲突","finding":"有证据","sourceNumbers":[1,90]},{"kind":"待核实","finding":"没有来源","sourceNumbers":[] }]}')
    result = await run(AssistantRequest(operation="review", title="A", content="x", sources=[{"bookId":1,"bookTitle":"B","sourceName":"B.md","excerpt":"e"}]), provider)
    assert result["findings"] == [{"kind": "内容冲突", "finding": "有证据", "sourceNumbers": [1]}]


@pytest.mark.asyncio
async def test_compose_preserves_only_service_supplied_source_metadata():
    provider = FakeProvider("# 草稿\n结论 [来源#1]")
    source = {"bookId": 7, "bookTitle": "Book", "sourceName": "a.pdf", "page": 3, "excerpt": "原文"}
    result = await run(AssistantRequest(operation="compose", goal="总结", sources=[source]), provider)
    assert result["content"] == "# 草稿\n结论 [来源#1]"
    assert result["sources"] == [source]


@pytest.mark.asyncio
async def test_summarize_consumes_prior_search_sources_instead_of_retrieval_instruction():
    provider = FakeProvider(json.dumps({"content": "Python 强调可读性。"}, ensure_ascii=False))
    source = {"bookId": 7, "bookTitle": "Book", "sourceName": "python.org", "excerpt": "Python emphasizes readability."}
    result = await run(AssistantRequest(operation="summarize", instructions="提炼作者核心观点", content="前序分析", sources=[source]), provider)
    assert result == {"content": "Python 强调可读性。", "sources": [source]}
    prompt = provider.calls[0][2]
    assert "Python emphasizes readability." in prompt
    assert "提炼作者核心观点" in prompt
    assert "前序分析" in prompt
    assert "不要把资料不足表述成“没有检索到出处”" in prompt


@pytest.mark.asyncio
async def test_summarize_requires_retrieved_sources():
    with pytest.raises(ModelUnavailable, match="前序检索到的来源片段"):
        await run(AssistantRequest(operation="summarize", instructions="提炼观点"), FakeProvider("{}"))


@pytest.mark.asyncio
async def test_compose_requires_sources_instead_of_drafting_a_no_evidence_answer():
    with pytest.raises(ModelUnavailable, match="前序检索到的来源片段"):
        await run(AssistantRequest(operation="compose", goal="翻译文章"), FakeProvider("空"))


@pytest.mark.asyncio
async def test_proofread_compares_the_prior_draft_with_supplied_original_sources():
    provider = FakeProvider(json.dumps({"content": "# 校对稿\nPython 易于阅读。", "findings": ["将术语改得更准确"]}, ensure_ascii=False))
    source = {"bookId": 7, "bookTitle": "Python.org", "sourceName": "原文.md", "fileId": 4, "page": 2, "excerpt": "Python's simple syntax emphasizes readability."}
    result = await run(AssistantRequest(operation="proofread", title="翻译", content="# 初稿\nPython 的简单语法强调阅读性。", instructions="对照英文校对", sources=[source]), provider)

    assert result == {"content": "# 校对稿\nPython 易于阅读。", "findings": ["将术语改得更准确"], "sources": [source]}
    prompt = provider.calls[0][2]
    assert "Python 的简单语法强调阅读性。" in prompt
    assert "Python's simple syntax emphasizes readability." in prompt
    assert "漏译、误译" in prompt


@pytest.mark.asyncio
async def test_proofread_requires_a_draft_and_source_evidence():
    provider = FakeProvider('{}')
    with pytest.raises(ModelUnavailable, match="前序生成的文稿"):
        await run(AssistantRequest(operation="proofread", content="初稿"), provider)


@pytest.mark.asyncio
async def test_learning_roadmap_requires_exact_schedule_and_keeps_only_real_source_numbers():
    provider = FakeProvider(json.dumps({
        "title": "Python 两周学习路线",
        "weeks": [
            {"focus": "基础语法", "outcome": "理解基础语法", "sessions": [
                {"title": "阅读变量章节", "activity": "阅读后用自己的话解释变量", "sourceNumbers": [1, 99, 1]},
                {"title": "练习条件判断", "activity": "完成两个 if 练习", "sourceNumbers": []},
            ]},
            {"focus": "函数", "outcome": "写出小函数", "sessions": [
                {"title": "阅读函数章节", "activity": "归纳参数和返回值", "sourceNumbers": [1]},
                {"title": "复盘函数", "activity": "独立写一个函数", "sourceNumbers": [True]},
            ]},
        ],
    }, ensure_ascii=False))
    source = {"bookId": 7, "bookTitle": "Python Book", "sourceName": "正文", "excerpt": "函数可以接收参数并返回结果。"}
    result = await run(AssistantRequest(operation="learning_roadmap", goal="学习 Python", level="初学者",
                                        weeks=2, sessions_per_week=2, sources=[source]), provider)
    assert len(result["weeks"]) == 2
    assert [len(week["sessions"]) for week in result["weeks"]] == [2, 2]
    assert result["weeks"][0]["sessions"][0]["sourceNumbers"] == [1]
    assert result["weeks"][1]["sessions"][1]["sourceNumbers"] == []
    assert "正好生成 2 周" in provider.calls[0][2]
    assert "Python Book" in provider.calls[0][2]


@pytest.mark.asyncio
async def test_learning_roadmap_rejects_incomplete_schedule():
    provider = FakeProvider('{"title":"短计划","weeks":[{"focus":"基础","sessions":[]}]}')
    with pytest.raises(ModelUnavailable, match="学习路线周数不完整"):
        await run(AssistantRequest(operation="learning_roadmap", goal="学习 Python", weeks=2, sources=[
            {"bookId": 7, "bookTitle": "Python Book", "sourceName": "正文", "excerpt": "内容"}
        ]), provider)


@pytest.mark.asyncio
async def test_learning_report_uses_self_reported_progress_and_returns_only_supplied_sources():
    provider = FakeProvider(json.dumps({
        "summary": "已完成两次学习。",
        "estimatedLevel": "基础阶段，尚未通过测验验证。",
        "strengths": ["能独立完成练习", "能独立完成练习"],
        "weakAreas": [{"topic": "条件判断", "reason": "自评信心较低", "practice": "复做条件练习"}],
        "nextSteps": ["复习条件判断"],
    }, ensure_ascii=False))
    source = {"bookId": 7, "bookTitle": "Python Book", "sourceName": "第 2 章", "page": 4, "excerpt": "条件判断"}
    result = await run(AssistantRequest(operation="learning_report", goal="学习 Python", level="初学者（起点信心 2/5）",
                                        content="完成了两次学习，条件判断自评 2/5。", sources=[source]), provider)
    assert result["summary"] == "已完成两次学习。"
    assert result["strengths"] == ["能独立完成练习"]
    assert result["weakAreas"][0]["topic"] == "条件判断"
    assert result["sources"] == [source]
    assert "并非正式考试" in provider.calls[0][2]



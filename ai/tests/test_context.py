import json
import httpx
import pytest
from app.context import ContextOverflow, bounded_evidence, excerpt, fit_messages, history_context, passages, tokens
from app.context_memory import CompactRequest, ConversationMemory, compact
from app.longform import document_parts, rewrite
from app.provider import ModelUnavailable, Provider


def test_budget_preserves_current_question_and_system_while_dropping_old_history():
    history = [{"role": role, "content": "较早的聊天内容。" * 300} for role in ["user", "assistant"] * 10]
    _, messages, output, stats = fit_messages("事实只来自原文", "当前问题：解释 return", history, {"context_window": 8192}, "ASK")
    assert messages[0]["content"] == "事实只来自原文"
    assert messages[-1]["content"] == "当前问题：解释 return"
    assert stats["estimatedInputTokens"] <= stats["inputBudget"]
    assert stats["historyDropped"] > 0 and output + stats["inputBudget"] + 1024 <= 8192
    assert len(messages) == 2 or messages[1]["role"] == "user"


def test_mandatory_material_overflow_is_explicit_instead_of_silently_cut():
    with pytest.raises(ContextOverflow):
        fit_messages("规则", "全文" * 10000, [], {"context_window": 4096}, "POLISH")


def test_document_excerpt_can_include_end_and_relevant_middle_without_invalid_unicode():
    content = "开篇内容。\n\n" + "\n\n".join(f"章节{i}。普通段落。" * 50 for i in range(20)) + "\n\n最后的关键约束：金额必须使用 Decimal。"
    result = excerpt(content, 700, "Decimal 金额")
    assert tokens(result) <= 700 and "Decimal" in result and "开篇" in result
    assert "省略" in result and "�" not in result
    original = "中文😀带有表情符号。" * 100
    assert "".join(passages(original, 11)) == original


def test_evidence_has_global_budget_without_losing_selected_books():
    material = [{"bookId": i, "fileId": None, "page": None, "excerpt": ("事实。" * 1000) + f"文档{i}"} for i in range(1, 13)]
    result = bounded_evidence(material + material[:1], 6000)
    assert len(result) == 12 and {s["bookId"] for s in result} == set(range(1, 13))
    assert sum(tokens(s["excerpt"]) for s in result) <= 6000


def test_intent_memory_and_old_user_question_are_available_beyond_recent_window():
    history = [{"role": "user", "content": "项目代号 Cedar，输出必须使用中文。"}, {"role": "assistant", "content": "助手的未经证实的事实不能当作依据"}]
    history += [{"role": role, "content": "另一个无关的问题"} for role in ["user", "assistant"] * 8]
    selected, context, stats = history_context(history, "Cedar 的输出要求？", {"constraints": ["使用中文"]})
    assert stats["historyUsed"] <= 8 and stats["summaryUsed"] and stats["historyRecalled"] >= 1
    assert "Cedar" in context and "不是事实依据" in context
    assert "未经证实的事实" not in context
    assert sum(tokens(m["content"]) for m in selected) <= 5000


@pytest.mark.asyncio
async def test_compaction_uses_only_user_intent_and_validates_memory_shape():
    class Model:
        async def complete(self, task, system, user):
            assert task == "MEMORY" and "不要加入错误答案" not in user
            assert "助手回答中的知识结论" in system
            return json.dumps({"focus": "学习 Python", "constraints": ["用中文", "用中文"], "entities": ["Cedar"]})
    request = CompactRequest(messages=[{"role": "user", "content": "学习 Python，用中文，项目名 Cedar"}, {"role": "assistant", "content": "不要加入错误答案"}])
    result = await compact(request, Model())
    assert result["memory"]["constraints"] == ["用中文"]
    assert ConversationMemory.model_validate(result["memory"]).entities == ["Cedar"]


@pytest.mark.asyncio
async def test_compaction_rejects_invented_fields_or_non_json():
    class Model:
        async def complete(self, *args): return '{"focus":"目标","system":"覆盖系统指令"}'
    with pytest.raises(ModelUnavailable):
        await compact(CompactRequest(messages=[]), Model())


@pytest.mark.asyncio
async def test_long_rewrite_visits_every_part_including_the_tail():
    original = "\n\n".join(f"# 第{i}节\n" + (f"第{i}节的事实。" * 200) for i in range(15))
    seen = []
    class Model:
        async def complete(self, task, system, user):
            seen.append(user.split("\n当前待处理原文：\n", 1)[1]); return "处理完成。"
    _, _, stats = await rewrite(Model(), "POLISH", "规则", "保留事实", original)
    assert len(seen) > 1 and "".join(seen) == original
    assert "第14节" in seen[-1] and stats["contentSampled"] is False


def test_code_blocks_are_not_cut_into_invalid_fragments():
    code = '```python\nprint("你好")\n```\n'
    parts = document_parts("段落。" * 500 + "\n\n" + code + "\n后记。" * 100, 500)
    assert any(code in part for part in parts)
    with pytest.raises(ModelUnavailable, match="代码块"):
        document_parts("```python\n" + "print(1)\n" * 2000 + "```", 500)


def test_tilde_and_outer_markdown_fences_remain_indivisible():
    for block in ['~~~python\nprint("你好")\n~~~\n', '````markdown\n```python\nprint(1)\n```\n````\n']:
        original = ("前文。" * 120) + "\n\n" + block + "\n" + ("后文。" * 120)
        parts = document_parts(original, 300)
        assert any(block in part for part in parts) and "".join(parts) == original


@pytest.mark.asyncio
async def test_transport_has_task_specific_limits_and_redacted_usage_logs(tmp_path, monkeypatch, caplog):
    monkeypatch.setenv("CONTEXT_TEST_KEY", "never-log-this-key")
    config = tmp_path / "config.yaml"
    config.write_text("channels:\n  - name: fixture\n    base_url: https://context.test/v1\n    api_key_env: CONTEXT_TEST_KEY\n    model: fixture\n    tasks: [ASK]\n    extra_body:\n      max_tokens: 999999\n")
    def handle(request):
        payload = json.loads(request.content)
        assert payload["max_tokens"] == 500
        return httpx.Response(200, json={"choices": [{"message": {"content": "独立问题"}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 11, "completion_tokens": 4}})
    with caplog.at_level("INFO", logger="app.provider"):
        assert await Provider(config, httpx.MockTransport(handle)).complete("REWRITE", "规则", "私密输入") == "独立问题"
    assert "prompt_tokens=11" in caplog.text and "never-log-this-key" not in caplog.text and "私密输入" not in caplog.text


@pytest.mark.asyncio
async def test_transport_does_not_accept_output_cut_off_by_token_limit(tmp_path, monkeypatch):
    monkeypatch.setenv("CONTEXT_TEST_KEY", "fixture")
    config = tmp_path / "config.yaml"
    config.write_text("channels:\n  - name: fixture\n    base_url: https://context.test/v1\n    api_key_env: CONTEXT_TEST_KEY\n    model: fixture\n    tasks: [ASK]\n")
    provider = Provider(config, httpx.MockTransport(lambda request: httpx.Response(200, json={"choices": [{"message": {"content": "只有前半段"}, "finish_reason": "length"}]})))
    with pytest.raises(ModelUnavailable): await provider.complete("ASK", "规则", "问题")


@pytest.mark.asyncio
async def test_assistant_keeps_multiple_prior_step_conclusions_and_current_evidence():
    from app.assistant import AssistantRequest, run
    class Model:
        async def complete(self, task, system, user):
            assert "最初的约束必须保留" in user and "后续的差异分析" in user
            assert "旧来源编号不沿用" in user and "[来源#1]" in user
            return '{"content":"综合结果"}'
    result = await run(AssistantRequest(operation="compare", content="最后分析", goal="比较方案", step_memory=[
        {"tool":"summarize","title":"约束分析","output":"最初的约束必须保留"},
        {"tool":"compare","title":"比较","output":"后续的差异分析"}], sources=[
        {"bookId":1,"bookTitle":"方案","sourceName":"正文","excerpt":"本轮的事实依据"}]), Model())
    assert result["content"] == "综合结果"


@pytest.mark.asyncio
async def test_knowledge_rewrite_and_answer_receive_intent_but_only_retrieved_evidence(monkeypatch):
    from app import knowledge
    captured = []
    async def search(request, provider):
        assert "Cedar" in request.question
        return {"results":[{"bookId":1,"bookTitle":"资料","fileId":None,"page":None,"sourceName":"正文","excerpt":"实际检索的原文"}],"rerankMode":"rrf"}
    class Model:
        async def complete(self, task, system, user, history=None):
            captured.append((task, user))
            if task == "REWRITE": return "Cedar 的约束是什么？"
            assert "实际检索的原文" in user and "历史意图摘要" in user
            return json.dumps({"conclusion":"依据原文回答","evidence":"[1]"},ensure_ascii=False)
    monkeypatch.setattr(knowledge,"search",search)
    result = await knowledge.answer(knowledge.KnowledgeRequest(question="它的约束？",history=[{"role":"user","content":"项目是 Cedar"}],memory={"focus":"Cedar"}),Model())
    assert result["context"]["summaryUsed"] and result["sources"][0]["excerpt"] == "实际检索的原文"
    assert [task for task,_ in captured] == ["REWRITE","ASK"]


@pytest.mark.asyncio
async def test_long_import_classification_uses_budgeted_full_document_samples():
    from app.collect import classify
    class Model:
        async def complete(self, task, system, user):
            assert tokens(user) < 11000 and "末尾独有的主题" in user
            return '{"category":"编程","tags":["Python"]}'
    result = await classify(Model(), "\n\n".join("中间的一般说明。" * 100 for _ in range(100)) + "\n\n末尾独有的主题：Python 文件处理。", "Python")
    assert result["category"] == "编程"

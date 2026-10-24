import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.answers import ConclusionStream, grounded_answer_stream, parse_answer
from app.knowledge import KnowledgeRequest, Scope
from app.provider import ModelUnavailable, Provider


def sse_body(*frames, done=True):
    lines = [f"data: {frame}" for frame in frames]
    if done:
        lines.append('data: {"choices":[{"delta":{},"finish_reason":"stop"}]}')
        lines.append("data: [DONE]")
    return ("\n\n".join(lines) + "\n\n").encode()


def delta(text):
    return json.dumps({"choices": [{"delta": {"content": text}}]}, ensure_ascii=False)


VALID_ANSWER = json.dumps({"conclusion": "答案依据文档。", "evidence": "出处即检索片段。[1]"}, ensure_ascii=False)


class StreamProvider:
    """complete/complete_stream fixture that replays scripted raw outputs."""

    def __init__(self, outputs):
        self.outputs = list(outputs)
        self.users = []

    async def complete(self, task, system, user, history=None):
        self.users.append(user)
        return self.outputs.pop(0)

    async def complete_stream(self, task, system, user, history=None):
        self.users.append(user)
        for piece in self.outputs.pop(0):
            yield piece


def collect(chunks):
    stream, out = ConclusionStream(), ""
    for chunk in chunks:
        out += stream.feed(chunk)
    return out


def test_conclusion_stream_unescapes_split_chunks():
    chunks = ['{"conclu', 'sion": "第一行\\n第二行\\"引', '号\\"，制表\\t结束", "evidence": "[1]"}']
    assert collect(chunks) == '第一行\n第二行"引号"，制表\t结束'


def test_conclusion_stream_handles_unicode_escapes_and_fences():
    chunks = ["```json\n", '{"conclusion": "\\u4e2d\\u6587\\"', '答案\\"', '", "evidence": "[1]"}']
    assert collect(chunks) == '中文"答案"'
    assert collect(['{"evidence": "[1]", "conclusion": "答案", "other": 1}']) == "答案"


def test_conclusion_stream_stops_at_closing_quote():
    stream = ConclusionStream()
    assert stream.feed('{"conclusion": "答案", "evidence": "[1]"}') == "答案"
    assert stream.feed("更多内容") == ""


@pytest.mark.asyncio
async def test_grounded_answer_stream_previews_then_validates():
    raw = '{"conclusion": "**结论依据文档。**", "evidence": "片段[1]"}'
    provider = StreamProvider([[raw[:14], raw[14:32], raw[32:]]])
    events = [event async for event in grounded_answer_stream(provider, "问题", "[1] 片段", 1, "当前 Book")]
    assert "".join(text for kind, text in events if kind == "delta") == "**结论依据文档。**"
    final = [value for kind, value in events if kind == "final"]
    assert len(final) == 1 and final[0] == parse_answer(raw, 1)


@pytest.mark.asyncio
async def test_grounded_answer_stream_resets_before_format_retry():
    provider = StreamProvider([["不是 JSON"], [VALID_ANSWER]])
    events = [event async for event in grounded_answer_stream(provider, "问题", "[1] 片段", 1, "当前 Book")]
    kinds = [kind for kind, _ in events]
    # The retry streams a fresh preview before its own validated final.
    assert kinds == ["reset", "delta", "final"]
    assert "".join(text for kind, text in events if kind == "delta") == "答案依据文档。"
    assert len(provider.users) == 2 and "格式校验未通过" in provider.users[1]


@pytest.mark.asyncio
async def test_grounded_answer_stream_fails_after_second_invalid_attempt():
    provider = StreamProvider([["随意"], ["仍然随意"]])
    with pytest.raises(ModelUnavailable):
        async for _ in grounded_answer_stream(provider, "问题", "[1] 片段", 1, "当前 Book"):
            pass


@pytest.mark.asyncio
async def test_provider_stream_parses_sse_and_falls_back_before_first_delta(tmp_path, monkeypatch):
    monkeypatch.setenv("TEST_LLM_KEY", "fixture-key")
    config = tmp_path / "config.yaml"
    config.write_text("channels:\n  - name: first\n    base_url: https://first.test/v1\n    api_key_env: TEST_LLM_KEY\n    model: x\n    tasks: [ASK]\n  - name: second\n    base_url: https://second.test/v1\n    api_key_env: TEST_LLM_KEY\n    model: y\n    tasks: [ASK]\n")
    called = []

    def handler(request):
        called.append(request.url.host)
        if request.url.host == "first.test":
            return httpx.Response(500, json={"error": "unavailable"})
        return httpx.Response(200, content=sse_body(delta("你好"), delta("，世界")), headers={"content-type": "text/event-stream"})

    provider = Provider(config, httpx.MockTransport(handler))
    assert [piece async for piece in provider.complete_stream("ASK", "system", "user")] == ["你好", "，世界"]
    assert called == ["first.test", "first.test", "second.test"]


@pytest.mark.asyncio
async def test_provider_stream_midstream_failure_never_mixes_channels(tmp_path, monkeypatch):
    monkeypatch.setenv("TEST_LLM_KEY", "fixture-key")
    config = tmp_path / "config.yaml"
    config.write_text("channels:\n  - name: first\n    base_url: https://first.test/v1\n    api_key_env: TEST_LLM_KEY\n    model: x\n    tasks: [ASK]\n  - name: second\n    base_url: https://second.test/v1\n    api_key_env: TEST_LLM_KEY\n    model: y\n    tasks: [ASK]\n")

    async def broken_body():
        yield sse_body(delta("部分答案"), done=False)
        raise ValueError("connection reset")

    def handler(request):
        return httpx.Response(200, content=broken_body(), headers={"content-type": "text/event-stream"})

    provider = Provider(config, httpx.MockTransport(handler))
    chunks = []
    with pytest.raises(ModelUnavailable, match="回答流中断"):
        async for piece in provider.complete_stream("ASK", "system", "user"):
            chunks.append(piece)
    assert chunks == ["部分答案"]


@pytest.mark.asyncio
async def test_provider_stream_reports_truncation(tmp_path, monkeypatch):
    monkeypatch.setenv("TEST_LLM_KEY", "fixture-key")
    config = tmp_path / "config.yaml"
    config.write_text("channels:\n  - name: only\n    base_url: https://only.test/v1\n    api_key_env: TEST_LLM_KEY\n    model: x\n    tasks: [ASK]\n")
    body = (delta("很长") + "\n\n").encode() + b'data: {"choices":[{"delta":{},"finish_reason":"length"}]}\n\n' + b"data: [DONE]\n\n"

    def handler(request):
        return httpx.Response(200, content=body, headers={"content-type": "text/event-stream"})

    provider = Provider(config, httpx.MockTransport(handler))
    with pytest.raises(ModelUnavailable, match="长度上限"):
        async for _ in provider.complete_stream("ASK", "system", "user"):
            pass


@pytest.mark.asyncio
async def test_knowledge_answer_stream_final_matches_contract(monkeypatch):
    from app import knowledge

    rows = [{"bookId": 1, "bookTitle": "书", "revision": 1, "chunkId": 9, "sourceName": "文档",
             "fileId": None, "chunkNo": 1, "page": 2, "excerpt": "答案就在这里", "score": 0.9}]

    async def fake_search(request, provider):
        return {"results": rows, "mode": request.search_mode, "rerankMode": "rrf"}

    monkeypatch.setattr(knowledge, "search", fake_search)
    provider = StreamProvider([[VALID_ANSWER]])
    request = KnowledgeRequest(question="问题", scopes=[Scope(book_id=1, revision=1, title="书")])
    events = [event async for event in knowledge.answer_stream(request, provider)]
    assert "".join(text for kind, text in events if kind == "delta") == "答案依据文档。"
    final = [value for kind, value in events if kind == "final"][0]
    assert final["answer"].startswith("**结论：**")
    assert final["sources"][0]["citation"] == 1 and final["sources"][0]["page"] == 2
    assert final["mode"] == "hybrid" and final["rerankMode"] == "rrf" and final["context"]["evidenceTokens"] > 0


@pytest.mark.asyncio
async def test_knowledge_answer_stream_skips_model_without_sources(monkeypatch):
    from app import knowledge

    async def fake_search(request, provider):
        return {"results": [], "mode": request.search_mode, "rerankMode": "none"}

    monkeypatch.setattr(knowledge, "search", fake_search)
    provider = StreamProvider([])
    request = KnowledgeRequest(question="问题")
    events = [event async for event in knowledge.answer_stream(request, provider)]
    assert [kind for kind, _ in events] == ["final"]
    assert "未检索到相关出处" in events[0][1]["answer"] and events[0][1]["sources"] == []


def parse_frames(body):
    frames = []
    for block in body.split("\n\n"):
        if not block.strip():
            continue
        event, data = None, None
        for line in block.split("\n"):
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:].strip())
        frames.append((event, data))
    return frames


def test_stream_endpoint_emits_start_delta_done(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "test-token")

    async def fake_stream(request, provider):
        yield ("delta", "预览")
        yield ("final", {"answer": "**结论：**", "sources": [], "mode": "hybrid", "rerankMode": "rrf", "context": {}})

    from app import main as app_main
    monkeypatch.setattr(app_main, "knowledge_answer_stream", fake_stream)
    with TestClient(app_main.app) as client:
        response = client.post("/internal/knowledge/ask/stream",
            headers={"X-Internal-Token": "test-token"}, json={"question": "问题"})
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert parse_frames(response.text) == [
        ("start", {"mode": "qa"}),
        ("delta", {"text": "预览"}),
        ("done", {"answer": "**结论：**", "sources": [], "mode": "hybrid", "rerankMode": "rrf", "context": {}}),
    ]


def test_stream_endpoint_requires_internal_token(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "test-token")
    from app import main as app_main
    with TestClient(app_main.app) as client:
        assert client.post("/internal/knowledge/ask/stream", json={"question": "问题"}).status_code == 403


def test_stream_endpoint_surfaces_model_error(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "test-token")

    async def broken_stream(request, provider):
        yield ("delta", "预览")
        raise ModelUnavailable("没有可用模型渠道")

    from app import main as app_main
    monkeypatch.setattr(app_main, "knowledge_answer_stream", broken_stream)
    with TestClient(app_main.app) as client:
        response = client.post("/internal/knowledge/ask/stream",
            headers={"X-Internal-Token": "test-token"}, json={"question": "问题"})
    frames = parse_frames(response.text)
    assert frames[-1] == ("error", {"message": "没有可用模型渠道"})
    assert [event for event, _ in frames].count("done") == 0

import json
import httpx
import pytest
from fastapi.testclient import TestClient
from app.models import Article, WriteRequest
from app.provider import Provider, ModelUnavailable
from app.retrieval import chunks, lexical, retrieve
from app.writing import WritingWorkflow
from app.main import app


def test_internal_auth_and_validation(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "test-token")
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert client.post("/internal/write", json={"topic":"x","stage":"OUTLINE"}).status_code == 403
        assert client.post("/internal/write", headers={"X-Internal-Token":"test-token"}, json={"topic":"x","stage":"PUBLISH"}).status_code == 422
        assert client.post("/internal/books/read",json={}).status_code==403
        assert client.post("/internal/ask",headers={"X-Internal-Token":"test-token"},json={"question":"x","articles":[],"history":[{"role":"system","content":"not allowed"}]}).status_code==422


def test_chinese_retrieval_and_unrelated_question():
    nodes = chunks([Article(id=1,title="Java 博客",content="使用 Spring Boot 构建博客。"),Article(id=2,title="园艺",content="阳台种植植物。")])
    assert lexical("如何构建博客", nodes)[0]["article_id"] == 1
    assert lexical("宇宙飞船", nodes) == []


@pytest.mark.asyncio
async def test_only_current_published_snapshot(monkeypatch):
    monkeypatch.delenv("PGVECTOR_DSN", raising=False)
    provider = Provider()
    result, mode = await retrieve("博客", [Article(id=2,title="博客",content="文章内容")], provider)
    assert mode == "lexical"
    assert {r["article_id"] for r in result} == {2}
    assert (await retrieve("博客", [], provider))[0] == []


@pytest.mark.asyncio
async def test_provider_retry_and_fallback(tmp_path, monkeypatch):
    monkeypatch.setenv("TEST_LLM_KEY", "fixture-key")
    config=tmp_path/"config.yaml"
    config.write_text("channels:\n  - name: first\n    base_url: https://first.test/v1\n    api_key_env: TEST_LLM_KEY\n    model: x\n    tasks: [OUTLINE]\n  - name: second\n    base_url: https://second.test/v1\n    api_key_env: TEST_LLM_KEY\n    model: y\n    tasks: [OUTLINE]\n")
    called=[]
    def handler(request):
        called.append(request.url.host)
        if request.url.host == "first.test": return httpx.Response(429,json={"error":"limited"})
        return httpx.Response(200,json={"choices":[{"message":{"content":"# 提纲"}}]})
    provider=Provider(config,httpx.MockTransport(handler))
    result=await WritingWorkflow(provider).run(request=WriteRequest(topic="博客",stage="OUTLINE").model_dump())
    assert result["output"] == "# 提纲"
    assert called == ["first.test","first.test","second.test"]


@pytest.mark.asyncio
async def test_no_key_is_not_fake_success(tmp_path):
    config=tmp_path/"config.yaml";config.write_text("channels: []")
    with pytest.raises(ModelUnavailable): await Provider(config).complete("OUTLINE","system","user")


def test_empty_knowledge_does_not_call_model(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN","test-token")
    with TestClient(app) as client:
        response=client.post("/internal/ask",headers={"X-Internal-Token":"test-token"},json={"question":"宇宙飞船","articles":[]})
        assert response.status_code == 200
        assert response.json()["sources"] == []

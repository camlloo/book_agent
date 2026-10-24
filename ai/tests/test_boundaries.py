"""Public retrieval/write boundaries with deterministic model and transport fixtures."""
import hashlib
import json

import pytest
from fastapi.testclient import TestClient

from app import main, book_rag, retrieval
from app.models import Article
from app.provider import Provider


@pytest.mark.parametrize("scope", [{"book_id": 1}, {"book_revision": 1}])
def test_write_requires_complete_book_scope(monkeypatch, scope):
    monkeypatch.setenv("INTERNAL_TOKEN", "fixture-token")
    with TestClient(main.app) as client:
        response = client.post("/internal/write", headers={"X-Internal-Token": "fixture-token"},
                               json={"topic": "蓝莓", "stage": "DRAFT", **scope})
    assert response.status_code == 422
    assert "book_id and book_revision" in response.text


def test_book_write_exposes_index_conflict_without_calling_model(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "fixture-token")
    class UnreadyStore:
        def __init__(self, provider): pass
        async def search(self, request):
            raise book_rag.IndexNotReady("Book 已更新或删除，请等待最新索引")
    class NoModel:
        async def complete(self, *args, **kwargs):
            pytest.fail("Unready Book must not call a model")
    monkeypatch.setattr(book_rag, "BookVectorStore", UnreadyStore)
    monkeypatch.setattr(main, "provider", NoModel())
    with TestClient(main.app) as client:
        response = client.post("/internal/write", headers={"X-Internal-Token": "fixture-token"},
                               json={"topic": "蓝莓", "stage": "DRAFT", "book_id": 1, "book_revision": 2})
    assert response.status_code == 409
    assert response.json()["detail"] == "Book 已更新或删除，请等待最新索引"


def test_book_write_retains_success_payload_and_reference_material(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "fixture-token")
    observed = []
    class ReadyStore:
        def __init__(self, provider): pass
        async def search(self, request):
            observed.append((request.book_id, request.revision))
            return [{"sourceName": "资料.pdf", "page": 3, "text": "蓝莓事实"}]
    class Model:
        async def complete(self, task, system, user):
            assert "资料.pdf，页码 3\n蓝莓事实" in user
            return "书稿正文"
    monkeypatch.setattr(book_rag, "BookVectorStore", ReadyStore)
    monkeypatch.setattr(main, "provider", Model())
    with TestClient(main.app) as client:
        response = client.post("/internal/write", headers={"X-Internal-Token": "fixture-token"},
                               json={"topic": "蓝莓", "stage": "DRAFT", "book_id": 1, "book_revision": 2})
    assert response.status_code == 200
    assert response.json() == {"output": "书稿正文", "stage": "DRAFT"}
    assert observed == [(1, 2)]


@pytest.mark.asyncio
@pytest.mark.parametrize("local", [False, True])
async def test_retrieval_uses_resolved_defaults_without_dimension_environment(tmp_path, monkeypatch, local):
    monkeypatch.setenv("PGVECTOR_DSN", "fixture-only")
    monkeypatch.setenv("FIXTURE_KEY", "fixture")
    monkeypatch.setenv("EMBEDDING_PROVIDER", "local" if local else "remote")
    monkeypatch.setenv("LOCAL_EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5")
    for key in ("EMBEDDING_BASE_URL", "EMBEDDING_API_KEY", "EMBEDDING_MODEL", "EMBEDDING_DIMENSIONS"):
        monkeypatch.delenv(key, raising=False)
    config = tmp_path / "config.yaml"
    config.write_text("embedding:\n  default_base_url: https://fixture.test\n  default_api_key_env: FIXTURE_KEY\n  default_model: defaults-model\n  default_dimensions: 3\n")
    provider = Provider(config)
    observed = []
    async def vector_search(question, nodes, supplied_provider, dsn):
        observed.append(supplied_provider.embedding_space())
        return nodes
    monkeypatch.setattr(retrieval, "vector_search", vector_search)
    result, mode = await retrieval.retrieve("蓝莓", [Article(id=1, title="种植", content="蓝莓事实")], provider)
    assert mode == "vector" and result[0]["article_id"] == 1
    assert observed[0].model == ("BAAI/bge-small-zh-v1.5" if local else "defaults-model")
    assert observed[0].dimensions == (512 if local else 3)
    # Both consumers resolve the same identity; existing Book namespaces survive.
    store = book_rag.BookVectorStore(provider)
    spec = provider.embedding_spec()
    old_book_identity = hashlib.sha256((spec["base_url"] + spec["model"] + str(spec["dimensions"])).encode()).hexdigest()[:12]
    assert store.identity == observed[0].identity == old_book_identity


def test_article_answer_retains_verified_concise_citations(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "fixture-token")
    class Model:
        async def complete(self, task, system, user, history=None):
            return json.dumps({"conclusion": "蓝莓代号是甲。", "evidence": "冗长说明[1]"})
    async def retrieve(question, articles, provider):
        return [{"article_id": 7, "title": "蓝莓", "text": "蓝莓代号是甲"}], "vector"
    monkeypatch.setattr(main, "provider", Model())
    monkeypatch.setattr(main, "retrieve", retrieve)
    with TestClient(main.app) as client:
        response = client.post("/internal/ask", headers={"X-Internal-Token": "fixture-token"},
                               json={"question": "蓝莓代号？", "articles": [{"id": 7, "title": "蓝莓", "content": "蓝莓代号是甲"}]})
    assert response.status_code == 200
    assert response.json() == {"answer": "**结论：**\n蓝莓代号是甲。\n\n**论据：**\n出处：[1]。",
                               "sources": [{"id": 7, "title": "蓝莓", "excerpt": "蓝莓代号是甲", "url": "/articles/7", "citation": 1}],
                               "mode": "vector"}

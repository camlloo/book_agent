"""Opt-in real pgvector smoke test; only its uniquely named test table is removed."""
import json
import os
import uuid

import httpx
import pytest
import psycopg
from app.models import Article
from app.retrieval import chunks, vector_search
from app.provider import EmbeddingSpace, Provider


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv("TEST_PGVECTOR_DSN"), reason="Set TEST_PGVECTOR_DSN to run against a test database")
async def test_vector_allowlist_revisions_and_dimensions(monkeypatch):
    model="qa-"+uuid.uuid4().hex
    monkeypatch.setenv("QA_EMBEDDING_MODEL",model)
    monkeypatch.setenv("QA_EMBEDDING_BASE","https://fixture.invalid")
    class FixtureProvider:
        def embedding_space(self): return EmbeddingSpace("https://fixture.invalid", model, 3)
        async def embeddings(self,texts):
            return [[1.0,0.0,0.0] if "博客" in text else [0.0,1.0,0.0] for text in texts]
    dsn=os.environ["TEST_PGVECTOR_DSN"]
    identity=FixtureProvider().embedding_space().identity
    table="book_agent_chunks_"+identity
    try:
        nodes=chunks([Article(id=1,title="博客",content="博客文章"),Article(id=2,title="植物",content="植物养护")])
        result=await vector_search("博客",nodes,FixtureProvider(),dsn,3)
        assert [r["article_id"] for r in result] == [1]
        # The old article still exists in the vector DB, but is outside the current allowlist.
        result=await vector_search("博客",nodes[1:],FixtureProvider(),dsn,3)
        assert result == []
        fresh=chunks([Article(id=1,title="博客",content="博客已修改的新正文")])
        result=await vector_search("博客",fresh,FixtureProvider(),dsn,3)
        assert result[0]["text"] == "博客已修改的新正文"
    finally:
        async with await psycopg.AsyncConnection.connect(dsn) as conn:
            await conn.execute(f"DROP TABLE IF EXISTS {table}")


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv("TEST_PGVECTOR_DSN"), reason="Set TEST_PGVECTOR_DSN to run against a test database")
async def test_resolved_default_models_have_separate_article_vector_caches(tmp_path, monkeypatch):
    monkeypatch.setenv("PGVECTOR_DSN", os.environ["TEST_PGVECTOR_DSN"])
    monkeypatch.setenv("FIXTURE_KEY", "fixture")
    monkeypatch.setenv("EMBEDDING_PROVIDER", "remote")
    for key in ("EMBEDDING_BASE_URL", "EMBEDDING_API_KEY", "EMBEDDING_MODEL", "EMBEDDING_DIMENSIONS"):
        monkeypatch.delenv(key, raising=False)
    namespace = uuid.uuid4().hex
    config = tmp_path / "config.yaml"
    config.write_text(f"embedding:\n  default_base_url: https://fixture.invalid\n  default_api_key_env: FIXTURE_KEY\n  default_model: {namespace}-first\n  default_dimensions: 3\n")
    observed = []
    def handler(request):
        body = json.loads(request.content)
        observed.append((body["model"], body["input"]))
        # Distinct model spaces must never reuse each other's cached vectors.
        vector = [1, 0, 0] if body["model"].endswith("first") else [0, 1, 0]
        return httpx.Response(200, json={"data": [{"index": i, "embedding": vector} for i in range(len(body["input"]))]})
    provider = Provider(config, httpx.MockTransport(handler))
    tables = []
    nodes = chunks([Article(id=1, title="蓝莓", content="蓝莓代号是甲")])
    try:
        for suffix in ("first", "second"):
            provider.config["embedding"]["default_model"] = f"{namespace}-{suffix}"
            tables.append("book_agent_chunks_" + provider.embedding_space().identity)
            result = await vector_search("蓝莓", nodes, provider, os.environ["TEST_PGVECTOR_DSN"])
            assert result[0]["article_id"] == 1
        assert tables[0] != tables[1]
        assert [model for model, texts in observed if texts == ["蓝莓\n蓝莓代号是甲"]] == [f"{namespace}-first", f"{namespace}-second"]
    finally:
        async with await psycopg.AsyncConnection.connect(os.environ["TEST_PGVECTOR_DSN"]) as conn:
            for table in tables:
                await conn.execute(f"DROP TABLE IF EXISTS {table}")

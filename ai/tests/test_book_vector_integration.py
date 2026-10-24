"""Opt-in pgvector regression. Uses unique namespaces and Book IDs; no paid models."""
import os
import asyncio
import uuid
import pytest
import psycopg
from app.book_rag import BookVectorStore, IndexNotReady
from app.documents import split_sections, Section
from app.models import BookIndexRequest, BookAskRequest
from app.provider import EmbeddingSpace


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv("TEST_PGVECTOR_DSN"), reason="Set TEST_PGVECTOR_DSN for a test database")
async def test_books_revisions_cache_cleanup_and_cross_model_tombstone(monkeypatch):
    monkeypatch.setenv("PGVECTOR_DSN", os.environ["TEST_PGVECTOR_DSN"])
    class Fixture:
        def __init__(self): self.model = uuid.uuid4().hex; self.calls = 0
        def embedding_spec(self): return {"base_url":"fixture", "model":self.model, "dimensions":3}
        def embedding_space(self): return EmbeddingSpace.from_spec(self.embedding_spec())
        async def embeddings(self, texts):
            self.calls += len(texts)
            return [[1,0,0] if "蓝莓" in text else [0,1,0] for text in texts]
    provider = Fixture(); other = Fixture()
    store = BookVectorStore(provider); alternate = BookVectorStore(other)
    id1 = 9_000_000_000_000 + uuid.uuid4().int % 1_000_000_000; id2 = id1 + 1
    def request(book_id, revision, content): return BookIndexRequest(book_id=book_id,revision=revision,title="测试",content=content)
    def nodes(r): return split_sections(r.book_id,r.title,"body","Book 正文",None,[Section(r.content)])
    query = BookAskRequest(book_id=id1, revision=1, question="蓝莓")
    try:
        r1 = request(id1,1,"蓝莓的代号是甲")
        await store.upsert(r1,nodes(r1))
        r2 = request(id2,1,"蓝莓的代号是秘密乙")
        await store.upsert(r2,nodes(r2))
        assert all("秘密乙" not in row["text"] for row in await store.search(query))
        calls = provider.calls
        await store.upsert(r1.model_copy(update={"revision":2}),nodes(r1))
        assert provider.calls == calls  # unchanged chunks reuse stored embeddings
        changed_page = nodes(r1); changed_page[0]["page_no"] = 3
        await store.upsert(r1.model_copy(update={"revision":2}),changed_page)
        assert (await store.search(query.model_copy(update={"revision":2})))[0]["page"] == 3
        with pytest.raises(IndexNotReady): await store.search(query)
        assert (await store.upsert(r1,nodes(r1)))["superseded"]
        await alternate.upsert(r1.model_copy(update={"revision":3}),nodes(r1))
        await alternate.delete(id1,4)
        assert (await store.upsert(r1.model_copy(update={"revision":3}),nodes(r1)))["superseded"]
        assert (await store.upsert(r1.model_copy(update={"revision":99}),nodes(r1)))["superseded"]
        with pytest.raises(IndexNotReady): await store.search(query.model_copy(update={"revision":2}))
        async with await store.connection() as conn:
            assert (await (await conn.execute(f"SELECT COUNT(*) FROM {store.table} WHERE book_id=%s",(id1,))).fetchone())[0] == 0
        # Same-revision retry must remove chunks omitted from the replacement manifest.
        await store.upsert(r2, [])
        assert await store.search(query.model_copy(update={"book_id":id2})) == []
    finally:
        async with await psycopg.AsyncConnection.connect(os.environ["TEST_PGVECTOR_DSN"]) as conn:
            for namespace in (store,alternate):
                await conn.execute(f"DROP TABLE IF EXISTS {namespace.table}")
                await conn.execute(f"DROP TABLE IF EXISTS {namespace.registry}")
            await conn.execute("DELETE FROM book_agent_state WHERE book_id=ANY(%s)",([id1,id2],))


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv("TEST_PGVECTOR_DSN"), reason="Set TEST_PGVECTOR_DSN for a test database")
@pytest.mark.parametrize("operation", ["update", "delete"])
async def test_revision_change_during_query_embedding_is_rejected(monkeypatch, operation):
    monkeypatch.setenv("PGVECTOR_DSN", os.environ["TEST_PGVECTOR_DSN"])
    entered, release = asyncio.Event(), asyncio.Event()

    class Fixture:
        def __init__(self): self.model = uuid.uuid4().hex
        def embedding_spec(self):
            return {"base_url": "fixture", "model": self.model, "dimensions": 3}
        def embedding_space(self): return EmbeddingSpace.from_spec(self.embedding_spec())
        async def embeddings(self, texts):
            if texts == ["查询蓝莓"]:
                entered.set()
                await release.wait()
            return [[1, 0, 0] for _ in texts]

    provider = Fixture()
    store = BookVectorStore(provider)
    book_id = 9_000_000_000_000 + uuid.uuid4().int % 1_000_000_000
    request = BookIndexRequest(book_id=book_id, revision=1, title="测试", content="蓝莓的代号是甲")
    def nodes(r): return split_sections(r.book_id, r.title, "body", "Book 正文", None, [Section(r.content)])
    pending = None
    try:
        await store.upsert(request, nodes(request))
        pending = asyncio.create_task(store.search(BookAskRequest(book_id=book_id, revision=1, question="查询蓝莓")))
        await asyncio.wait_for(entered.wait(), timeout=5)
        # This must complete while embedding is suspended, without a database lock.
        if operation == "update":
            updated = request.model_copy(update={"revision": 2, "content": "蓝莓的代号是乙"})
            await asyncio.wait_for(store.upsert(updated, nodes(updated)), timeout=5)
        else:
            await asyncio.wait_for(store.delete(book_id, 2), timeout=5)
        release.set()
        with pytest.raises(IndexNotReady):
            await asyncio.wait_for(pending, timeout=5)
    finally:
        release.set()
        if pending is not None:
            pending.cancel()
            await asyncio.gather(pending, return_exceptions=True)
        async with await psycopg.AsyncConnection.connect(os.environ["TEST_PGVECTOR_DSN"]) as conn:
            await conn.execute(f"DROP TABLE IF EXISTS {store.table}")
            await conn.execute(f"DROP TABLE IF EXISTS {store.registry}")
            await conn.execute("DELETE FROM book_agent_state WHERE book_id=%s", (book_id,))

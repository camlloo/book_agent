import os
import re

import numpy as np
import psycopg
from pgvector.psycopg import register_vector_async

from .documents import build_nodes
from .answers import empty_answer, grounded_answer
from .models import BookIndexRequest, BookAskRequest
from .provider import Provider, ModelUnavailable
from .retrieval import tokens


class IndexNotReady(ValueError):
    pass


class BookVectorStore:
    """Per-book versioned index. Never rebuild a corpus during query or erase other Books."""
    def __init__(self, provider: Provider):
        self.provider = provider
        space = provider.embedding_space()
        self.dimensions = space.dimensions
        self.identity = space.identity
        self.table = "book_agent_documents_" + self.identity
        self.registry = "book_agent_versions_" + self.identity
        self.dsn = os.getenv("PGVECTOR_DSN", "")
        if not self.dsn:
            raise ModelUnavailable("未配置 PGVECTOR_DSN，请先运行 Setup-RAG.ps1")

    async def connection(self):
        connection = await psycopg.AsyncConnection.connect(self.dsn)
        try:
            await register_vector_async(connection)
            # Serialize first-use DDL across query/index requests and service processes.
            await connection.execute("SELECT pg_advisory_xact_lock(-782314921)")
            await connection.execute("CREATE TABLE IF NOT EXISTS book_agent_state(book_id BIGINT PRIMARY KEY,revision BIGINT NOT NULL,deleted BOOLEAN NOT NULL DEFAULT FALSE)")
            await connection.execute(f"""CREATE TABLE IF NOT EXISTS {self.table} (
                chunk_id TEXT PRIMARY KEY, book_id BIGINT NOT NULL, revision BIGINT NOT NULL,
                source_key TEXT NOT NULL, source_name TEXT NOT NULL, file_id BIGINT,
                chunk_no INT NOT NULL, page_no INT, content TEXT NOT NULL,
                embedding vector({self.dimensions}) NOT NULL)""")
            await connection.execute(f"CREATE INDEX IF NOT EXISTS {self.table}_scope ON {self.table}(book_id,revision)")
            await connection.execute(f"CREATE INDEX IF NOT EXISTS {self.table}_hnsw ON {self.table} USING hnsw(embedding vector_cosine_ops)")
            await connection.execute(f"CREATE TABLE IF NOT EXISTS {self.registry}(book_id BIGINT PRIMARY KEY,revision BIGINT NOT NULL,deleted BOOLEAN NOT NULL DEFAULT FALSE,chunk_count INT NOT NULL DEFAULT 0)")
            await connection.execute(f"ALTER TABLE {self.table} ADD COLUMN IF NOT EXISTS search_vector tsvector")
            pending = await (await connection.execute(f"SELECT chunk_id,content FROM {self.table} WHERE search_vector IS NULL")).fetchall()
            for chunk_id, text in pending:
                await connection.execute(f"UPDATE {self.table} SET search_vector=to_tsvector('simple',%s) WHERE chunk_id=%s", (" ".join(sorted(tokens(text))),chunk_id))
            await connection.execute(f"CREATE INDEX IF NOT EXISTS {self.table}_fulltext ON {self.table} USING gin(search_vector)")
            await connection.commit()
            return connection
        except Exception:
            await connection.close()
            raise

    async def upsert(self, request: BookIndexRequest, nodes: list[dict]) -> dict:
        async with await self.connection() as connection:
            rows = await (await connection.execute("SELECT revision,deleted FROM book_agent_state WHERE book_id=%s", (request.book_id,))).fetchall()
            if rows and (rows[0][0] > request.revision or rows[0][1]):
                return {"superseded": True, "chunk_count": 0}
            ids = [node["chunk_id"] for node in nodes]
            known = await (await connection.execute(f"SELECT chunk_id,embedding FROM {self.table} WHERE book_id=%s AND chunk_id=ANY(%s)", (request.book_id, ids))).fetchall()
            vectors = {row[0]: row[1] for row in known}
        missing = [node for node in nodes if node["chunk_id"] not in vectors]
        # Do model calls outside transactions; final application is one atomic version switch.
        for offset in range(0, len(missing), 10):
            batch = missing[offset:offset + 10]
            generated = await self.provider.embeddings([node["embed_text"] for node in batch])
            if len(generated) != len(batch) or any(len(vector) != self.dimensions for vector in generated):
                raise ModelUnavailable("Embedding 结果数量或维度不一致")
            vectors.update((node["chunk_id"], np.asarray(vector, dtype=np.float32)) for node, vector in zip(batch, generated))
        async with await self.connection() as connection:
            await connection.execute("SELECT pg_advisory_xact_lock(%s)", (request.book_id,))
            current = await (await connection.execute("SELECT revision,deleted FROM book_agent_state WHERE book_id=%s", (request.book_id,))).fetchone()
            if current and (current[0] > request.revision or current[1]):
                return {"superseded": True, "chunk_count": 0}
            for node in nodes:
                await connection.execute(f"""INSERT INTO {self.table}(chunk_id,book_id,revision,source_key,source_name,file_id,chunk_no,page_no,content,embedding,search_vector)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,to_tsvector('simple',%s))
                    ON CONFLICT(chunk_id) DO UPDATE SET revision=EXCLUDED.revision,page_no=EXCLUDED.page_no,
                    source_name=EXCLUDED.source_name,chunk_no=EXCLUDED.chunk_no,file_id=EXCLUDED.file_id""",
                    (node["chunk_id"], request.book_id, request.revision, node["source_key"], node["source_name"], node["file_id"], node["chunk_no"], node["page_no"], node["text"], vectors[node["chunk_id"]]," ".join(sorted(tokens(node["text"])))) )
            await connection.execute(f"DELETE FROM {self.table} WHERE book_id=%s AND NOT (chunk_id=ANY(%s))", (request.book_id, ids))
            await connection.execute(f"INSERT INTO {self.registry}(book_id,revision,deleted,chunk_count) VALUES(%s,%s,FALSE,%s) ON CONFLICT(book_id) DO UPDATE SET revision=EXCLUDED.revision,deleted=FALSE,chunk_count=EXCLUDED.chunk_count", (request.book_id, request.revision, len(nodes)))
            await connection.execute("INSERT INTO book_agent_state(book_id,revision,deleted) VALUES(%s,%s,FALSE) ON CONFLICT(book_id) DO UPDATE SET revision=EXCLUDED.revision,deleted=FALSE", (request.book_id, request.revision))
        return {"chunk_count": len(nodes), "revision": request.revision, "embedding_model": self.provider.embedding_spec()["model"], "dimensions": self.dimensions}

    async def delete(self, book_id: int, revision: int) -> dict:
        async with await self.connection() as connection:
            await connection.execute("SELECT pg_advisory_xact_lock(%s)", (book_id,))
            current = await (await connection.execute("SELECT revision FROM book_agent_state WHERE book_id=%s", (book_id,))).fetchone()
            if current and current[0] > revision:
                return {"superseded": True}
            tables = await (await connection.execute("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND tablename LIKE 'book_agent_documents_%' AND tableowner=current_user")).fetchall()
            for (table,) in tables:
                if re.fullmatch(r"book_agent_documents_[a-f0-9]{12}", table):
                    await connection.execute(f"DELETE FROM {table} WHERE book_id=%s", (book_id,))
            await connection.execute(f"INSERT INTO {self.registry}(book_id,revision,deleted,chunk_count) VALUES(%s,%s,TRUE,0) ON CONFLICT(book_id) DO UPDATE SET revision=EXCLUDED.revision,deleted=TRUE,chunk_count=0", (book_id, revision))
            await connection.execute("INSERT INTO book_agent_state(book_id,revision,deleted) VALUES(%s,%s,TRUE) ON CONFLICT(book_id) DO UPDATE SET revision=EXCLUDED.revision,deleted=TRUE", (book_id, revision))
        return {"deleted": True}

    async def _ready_chunk_count(self, connection, request: BookAskRequest) -> int:
        state = await (await connection.execute("SELECT revision,deleted FROM book_agent_state WHERE book_id=%s", (request.book_id,))).fetchone()
        if not state or state[0] != request.revision or state[1]:
            raise IndexNotReady("Book 已更新或删除，请等待最新索引")
        current = await (await connection.execute(f"SELECT revision,deleted,chunk_count FROM {self.registry} WHERE book_id=%s", (request.book_id,))).fetchone()
        if not current or current[0] != request.revision or current[1]:
            raise IndexNotReady("向量索引版本与 Book 不一致，请重新建立索引")
        return current[2]

    async def search(self, request: BookAskRequest) -> list[dict]:
        # Reject unready/empty indexes without spending a model call.
        async with await self.connection() as connection:
            await connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
            if await self._ready_chunk_count(connection, request) == 0:
                return []
        # No database transaction or lock survives this remote/local model await.
        generated = await self.provider.embeddings([request.question])
        if len(generated) != 1 or len(generated[0]) != self.dimensions:
            raise ModelUnavailable("Embedding 结果数量或维度不一致")
        vector = np.asarray(generated[0], dtype=np.float32)
        async with await self.connection() as connection:
            # Read state, semantic candidates and lexical candidates from one snapshot.
            await connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
            if await self._ready_chunk_count(connection, request) == 0:
                return []
            # Metadata filter is mandatory, including for lexical recall.
            rows = await (await connection.execute(f"""SELECT chunk_id,source_name,file_id,chunk_no,page_no,content,1-(embedding <=> %s::vector) AS similarity
                FROM {self.table} WHERE book_id=%s AND revision=%s ORDER BY embedding <=> %s::vector LIMIT 30""", (vector, request.book_id, request.revision, vector))).fetchall()
            query_tokens = tokens(request.question)
            # Keyword recall recovers exact terms that semantic top-k can miss (bounded by 10k chunks).
            snippets = await (await connection.execute(f"SELECT chunk_id,source_name,file_id,chunk_no,page_no,content FROM {self.table} WHERE book_id=%s AND revision=%s", (request.book_id, request.revision))).fetchall()
        candidates = {row[0]: row for row in rows}
        for row in snippets:
            if query_tokens & tokens(row[5]):
                candidates.setdefault(row[0], (*row, 0.0))
        ranked = []
        minimum = float(os.getenv("RAG_MIN_SIMILARITY", "0.45"))
        for row in candidates.values():
            overlap = len(query_tokens & tokens(row[5])) / max(1, len(query_tokens))
            if row[6] < minimum and overlap < 0.25:
                continue
            score = 0.75 * max(0, row[6]) + 0.25 * overlap
            ranked.append({"chunkId": row[0], "sourceName": row[1], "fileId": row[2], "chunkNo": row[3], "page": row[4], "text": row[5], "score": round(score, 4)})
        ranked.sort(key=lambda node: node["score"], reverse=True)
        return ranked[:request.top_k]


async def index_book(request: BookIndexRequest, provider: Provider) -> dict:
    if request.chunking == "semantic":
        from .semantic import semantic_nodes
        nodes = await semantic_nodes(request,provider)
    else: nodes = await build_nodes(request)
    return await BookVectorStore(provider).upsert(request, nodes)


async def answer_book(request: BookAskRequest, provider: Provider) -> dict:
    previous = next((item.content[:150] for item in reversed(request.history) if item.role == "user"), "")
    retrieval_request = request.model_copy(update={"question": request.question + " " + previous})
    nodes = await BookVectorStore(provider).search(retrieval_request)
    if not nodes:
        return {"answer": empty_answer("当前 Book 的当前就绪版本"), "sources": [], "mode": "hybrid"}
    evidence = "\n\n".join(f"[{i + 1}] {node['sourceName']}（页码 {node['page'] or '无'}，分块 {node['chunkNo']}）\n{node['text']}" for i, node in enumerate(nodes))
    answer = await grounded_answer(provider, request.question, evidence, len(nodes),
        "当前 Book 的当前就绪版本", history=[item.model_dump() for item in request.history],
        source_keys=[(node["fileId"], node["sourceName"], node["page"]) for node in nodes])
    sources = [{"citation": i + 1, "bookId": request.book_id, **{key: value for key, value in node.items() if key != "text"}, "excerpt": node["text"]} for i, node in enumerate(nodes)]
    return {"answer": answer, "sources": sources, "mode": "hybrid"}

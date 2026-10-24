import hashlib
import logging
import os
import re
from collections import Counter

from llama_index.core.node_parser import SentenceSplitter
from .models import Article
from .provider import Provider

logger = logging.getLogger(__name__)


def tokens(text: str) -> set[str]:
    latin = re.findall(r"[a-z0-9_]{2,}", text.lower())
    chinese = re.findall(r"[\u4e00-\u9fff]+", text)
    return set(latin + [word[i:i+2] for word in chinese for i in range(max(1, len(word)-1))])


def chunks(articles: list[Article]) -> list[dict]:
    splitter = SentenceSplitter(chunk_size=650, chunk_overlap=80)
    result = []
    for article in articles:
        for index, text in enumerate(splitter.split_text(article.content)):
            revision = hashlib.sha256((article.title + "\n" + text).encode()).hexdigest()
            result.append({"article_id": article.id, "title": article.title, "text": text,
                "chunk_id": f"{article.id}:{index}:{revision}", "revision": revision})
    return result


def lexical(question: str, nodes: list[dict], limit=5) -> list[dict]:
    query = tokens(question)
    document_tokens = [tokens(node["title"] + " " + node["text"]) for node in nodes]
    frequency = Counter(token for words in document_tokens for token in words)
    scored = []
    for node, words in zip(nodes, document_tokens):
        overlap = query & words
        if overlap:
            score = sum(1 / frequency[token] for token in overlap) + 2 * len(query & tokens(node["title"]))
            scored.append((score, node))
    return [node for _, node in sorted(scored, key=lambda item: item[0], reverse=True)[:limit]]


async def retrieve(question: str, articles: list[Article], provider: Provider) -> tuple[list[dict], str]:
    nodes = chunks(articles)
    if not nodes:
        return [], "lexical"
    dsn = os.getenv("PGVECTOR_DSN", "")
    if not dsn:
        return lexical(question, nodes), "lexical"
    try:
        return await vector_search(question, nodes, provider, dsn), "vector"
    except Exception as exc:
        logger.warning("Semantic retrieval unavailable (%s); using lexical retrieval", type(exc).__name__)
        return lexical(question, nodes), "lexical"


async def vector_search(question, nodes, provider, dsn, dimensions=None):
    import psycopg
    import numpy as np
    from pgvector.psycopg import register_vector_async
    space = provider.embedding_space()
    if dimensions is not None and dimensions != space.dimensions:
        raise ValueError("Embedding dimensions differ from the configured vector space")
    dimensions = space.dimensions
    table = f"book_agent_chunks_{space.identity}"
    async with await psycopg.AsyncConnection.connect(dsn) as conn:
        await conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        await register_vector_async(conn)
        await conn.execute(f"CREATE TABLE IF NOT EXISTS {table} (chunk_id TEXT PRIMARY KEY,article_id BIGINT NOT NULL,embedding vector({dimensions}) NOT NULL)")
        await conn.execute(f"CREATE INDEX IF NOT EXISTS {table}_hnsw ON {table} USING hnsw (embedding vector_cosine_ops)")
        ids = [node["chunk_id"] for node in nodes]
        existing = await (await conn.execute(f"SELECT chunk_id FROM {table} WHERE chunk_id=ANY(%s)", (ids,))).fetchall()
        known = {row[0] for row in existing}
        missing = [node for node in nodes if node["chunk_id"] not in known]
        for offset in range(0, len(missing), 16):
            batch = missing[offset:offset+16]
            vectors = await provider.embeddings([node["title"] + "\n" + node["text"] for node in batch])
            for node, vector in zip(batch, vectors):
                if len(vector) != dimensions:
                    raise ValueError("Embedding dimension mismatch")
                await conn.execute(f"INSERT INTO {table}(chunk_id,article_id,embedding) VALUES(%s,%s,%s) ON CONFLICT(chunk_id) DO NOTHING", (node["chunk_id"],node["article_id"],np.asarray(vector,dtype=np.float32)))
        query = (await provider.embeddings([question]))[0]
        if len(query) != dimensions:
            raise ValueError("Embedding dimension mismatch")
        # Exact currently published chunk ids are the allowlist. Offline/deleted/old revisions can never be returned.
        rows = await (await conn.execute(f"SELECT chunk_id,embedding <=> %s::vector AS distance FROM {table} WHERE chunk_id=ANY(%s) ORDER BY distance LIMIT 5", (np.asarray(query,dtype=np.float32), ids))).fetchall()
        await conn.execute(f"DELETE FROM {table} WHERE NOT (chunk_id=ANY(%s))", (ids,))
        lookup = {node["chunk_id"]: node for node in nodes}
        return [lookup[row[0]] for row in rows if row[1] < 0.65]

import asyncio
import hmac
import json
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Depends, Header, HTTPException
from .models import AskRequest, WriteRequest, BookIndexRequest, BookAskRequest, BookDeleteRequest
from .documents import DocumentError
from .book_rag import BookVectorStore, IndexNotReady, index_book, answer_book
from .provider import ModelUnavailable, Provider
from .documents import read_book
from .retrieval import retrieve
from .answers import empty_answer, grounded_answer
from .writing import WritingWorkflow

provider = Provider()
capacity = asyncio.Semaphore(2)
index_capacity = asyncio.Semaphore(1)
read_capacity = asyncio.Semaphore(2)


@asynccontextmanager
async def lifespan(app):
    if not os.getenv("INTERNAL_TOKEN", ""):
        raise RuntimeError("INTERNAL_TOKEN must be set before starting the AI service")
    yield


app = FastAPI(title="Book_Agent AI", lifespan=lifespan)


def internal_auth(x_internal_token: str | None = Header(default=None)):
    expected = os.getenv("INTERNAL_TOKEN", "")
    if not expected or not x_internal_token or not hmac.compare_digest(expected, x_internal_token):
        raise HTTPException(403, "Invalid internal token")


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/internal/write", dependencies=[Depends(internal_auth)])
async def write(request: WriteRequest):
    async with capacity:
        try:
            return await WritingWorkflow(provider).run(request=request.model_dump())
        except IndexNotReady as exc:
            raise HTTPException(409, str(exc)) from exc
        except ModelUnavailable as exc:
            raise HTTPException(503, str(exc)) from exc


@app.post("/internal/ask", dependencies=[Depends(internal_auth)])
async def ask(request: AskRequest):
    async with capacity:
        previous = next((item.content[:150] for item in reversed(request.history) if item.role == "user"), "")
        nodes, mode = await retrieve(request.question + " " + previous, request.articles, provider)
        if not nodes:
            return {"answer": empty_answer("业务服务提供的已发布内容快照"), "sources": [], "mode": mode}
        evidence = "\n\n".join(f"[{i+1}] 《{node['title']}》\n{node['text']}" for i, node in enumerate(nodes))
        try:
            answer = await grounded_answer(provider, request.question, evidence, len(nodes),
                "业务服务提供的已发布内容快照", history=[item.model_dump() for item in request.history],
                source_keys=[node["article_id"] for node in nodes])
        except ModelUnavailable as exc:
            raise HTTPException(503, str(exc)) from exc
        sources = [{"id": node["article_id"], "title": node["title"], "excerpt": node["text"][:300],
                    "url": f"/articles/{node['article_id']}", "citation": i+1} for i, node in enumerate(nodes)]
        return {"answer": answer, "sources": sources, "mode": mode}


@app.post("/internal/books/index", dependencies=[Depends(internal_auth)])
async def book_index(request: BookIndexRequest):
    async with index_capacity:
        try:
            return await index_book(request, provider)
        except DocumentError as error:
            raise HTTPException(422, str(error)) from error
        except ModelUnavailable as error:
            raise HTTPException(503, str(error)) from error
        except Exception:
            raise HTTPException(503, "向量数据库连接或索引写入失败，请检查 PGVECTOR_DSN 与服务日志") from None


@app.post("/internal/books/delete", dependencies=[Depends(internal_auth)])
async def book_delete(request: BookDeleteRequest):
    async with index_capacity:
        try:
            return await BookVectorStore(provider).delete(request.book_id, request.revision)
        except Exception:
            raise HTTPException(503, "向量删除失败，请检查向量库连接，系统会重试") from None


@app.post("/internal/books/ask", dependencies=[Depends(internal_auth)])
async def book_ask(request: BookAskRequest):
    async with capacity:
        try:
            return await answer_book(request, provider)
        except IndexNotReady as error:
            raise HTTPException(409, str(error)) from error
        except ModelUnavailable as error:
            raise HTTPException(503, str(error)) from error
        except Exception:
            raise HTTPException(503, "Book 检索服务不可用，请检查向量库与模型配置") from None


@app.post("/internal/books/read", dependencies=[Depends(internal_auth)])
async def book_read(request: BookIndexRequest):
    async with read_capacity:
        try:
            return await read_book(request)
        except DocumentError as error:
            raise HTTPException(422, str(error)) from None


# Personal knowledge collection/export endpoints share the internal trust boundary.
from pydantic import BaseModel, Field
from typing import Literal
from fastapi.responses import Response
from .collect import collect, classify, CollectionError
from .pdf_export import export_pdf

class CollectionRequest(BaseModel):
    kind: Literal["WEB", "REPO"]
    url: str = Field(min_length=1, max_length=2000)
    branch: str = Field(default="", max_length=128)
    path: str = Field(default="", max_length=255)

@app.post("/internal/collect", dependencies=[Depends(internal_auth)])
async def collect_source(request: CollectionRequest):
    try:
        return await collect(request.kind, request.url, request.branch, request.path)
    except CollectionError as error:
        raise HTTPException(422, str(error)) from None
    except Exception:
        raise HTTPException(503, "来源采集失败，请检查地址和服务端仓库令牌") from None

class TextRequest(BaseModel):
    title: str = Field(max_length=128)
    content: str = Field(max_length=2_000_000)

@app.post("/internal/classify", dependencies=[Depends(internal_auth)])
async def classify_text(request: TextRequest):
    async with capacity:
        try: return await classify(provider, request.content, request.title)
        except (CollectionError, ModelUnavailable) as error:
            raise HTTPException(503, str(error)) from None

@app.post("/internal/export/pdf", dependencies=[Depends(internal_auth)])
async def pdf(request: TextRequest):
    return Response(await asyncio.to_thread(export_pdf, request.title, request.content), media_type="application/pdf")


from .knowledge import KnowledgeRequest, search as knowledge_search, answer as knowledge_answer, answer_stream as knowledge_answer_stream
from .assistant import AssistantRequest, run as run_assistant
from .context_memory import CompactRequest, compact

@app.post("/internal/context/compact", dependencies=[Depends(internal_auth)])
async def compact_context(request: CompactRequest):
    async with capacity:
        try: return await compact(request, provider)
        except ModelUnavailable as error: raise HTTPException(503, str(error)) from None

@app.post("/internal/knowledge/search", dependencies=[Depends(internal_auth)])
async def search_knowledge(request: KnowledgeRequest):
    async with capacity:
        try: return await knowledge_search(request, provider)
        except IndexNotReady as error: raise HTTPException(409,str(error)) from None
        except ModelUnavailable as error: raise HTTPException(503,str(error)) from None
        except Exception: raise HTTPException(503,"全库检索服务不可用，请检查向量库与配置") from None

@app.post("/internal/knowledge/ask", dependencies=[Depends(internal_auth)])
async def ask_knowledge(request: KnowledgeRequest):
    async with capacity:
        try: return await knowledge_answer(request,provider)
        except IndexNotReady as error: raise HTTPException(409,str(error)) from None
        except ModelUnavailable as error: raise HTTPException(503,str(error)) from None
        except Exception: raise HTTPException(503,"全库问答服务不可用，请检查向量库与模型") from None


def _sse(event: str, data) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@app.post("/internal/knowledge/ask/stream", dependencies=[Depends(internal_auth)])
async def ask_knowledge_stream(request: KnowledgeRequest):
    """Server-sent events: start/delta/reset preview chunks, then one done frame
    with the validated full result (same shape as /internal/knowledge/ask), or error."""
    from fastapi.responses import StreamingResponse

    async def events():
        async with capacity:
            try:
                yield _sse("start", {"mode": request.mode})
                async for kind, value in knowledge_answer_stream(request, provider):
                    if kind == "final":
                        yield _sse("done", value)
                    elif kind == "reset":
                        yield _sse("reset", {})
                    else:
                        yield _sse("delta", {"text": value})
            except IndexNotReady as error:
                yield _sse("error", {"message": str(error)})
            except ModelUnavailable as error:
                yield _sse("error", {"message": str(error)})
            except Exception:
                yield _sse("error", {"message": "全库问答服务不可用，请检查向量库与模型"})

    return StreamingResponse(events(), media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/internal/assistant", dependencies=[Depends(internal_auth)])
async def assistant(request: AssistantRequest):
    async with capacity:
        try:
            return await run_assistant(request, provider)
        except ModelUnavailable as error:
            raise HTTPException(503, str(error)) from None

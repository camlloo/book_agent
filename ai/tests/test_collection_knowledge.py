import io
import json
import zipfile
import asyncio
import uuid
import os
import pytest
import httpx
from app import collect as collection
from app.collect import CollectionError, extract_html, repository_document
from app.provider import Provider, EmbeddingSpace, ModelUnavailable
from app.pdf_export import export_pdf
from app.knowledge import KnowledgeRequest, Scope, search
from app.book_rag import BookVectorStore, IndexNotReady
from app.models import BookIndexRequest
from app.documents import Section, split_sections


def test_web_extraction_removes_navigation_and_keeps_source_and_code():
    body = '<html><title>Python 实践</title><body><nav>不应保留导航</nav><article><h1>资料标题</h1><p>' + '可靠的正文知识。'*15 + '</p><pre>print(1)\nprint(2)</pre><script>ignore()</script></article></body></html>'
    result = extract_html(body.encode(), 'https://example.com/article')
    assert result['title']=='Python 实践'
    assert 'print(1)\nprint(2)' in result['content'] and '不应保留导航' not in result['content'] and 'ignore()' not in result['content']
    assert '来源：https://example.com/article' in result['content'] and len(result['source_hash'])==64


@pytest.mark.asyncio
@pytest.mark.parametrize('address',['127.0.0.1','10.1.2.3','169.254.169.254','::1','192.168.1.1'])
async def test_collector_rejects_nonpublic_dns(monkeypatch,address):
    monkeypatch.setattr(collection.socket,'getaddrinfo',lambda *args:[(0,0,0,'',(address,443))])
    with pytest.raises(CollectionError,match='公网地址'):
        await collection.public_address('fixture.example',443)


@pytest.mark.asyncio
async def test_redirect_revalidates_dns_and_drops_repository_tokens(monkeypatch):
    calls=[]
    async def resolve(host,port):
        calls.append(host)
        return '93.184.216.34'
    def handle(request):
        assert request.url.host=='93.184.216.34'
        if request.headers['host']=='first.example':
            assert request.headers['authorization']=='Bearer fixture'
            return httpx.Response(302,headers={'location':'https://second.example/result'})
        assert 'authorization' not in request.headers
        assert request.extensions['sni_hostname']=='second.example'
        return httpx.Response(200,content=b'fixture')
    original=httpx.AsyncClient
    monkeypatch.setattr(collection,'public_address',resolve)
    monkeypatch.setattr(collection.httpx,'AsyncClient',lambda **kwargs:original(transport=httpx.MockTransport(handle),**kwargs))
    data,_,url=await collection.fetch('https://first.example',headers={'Authorization':'Bearer fixture'})
    assert data==b'fixture' and url=='https://second.example/result' and calls==['first.example','second.example']


def test_repository_snapshot_skips_secrets_binaries_and_unsafe_paths():
    output=io.BytesIO()
    with zipfile.ZipFile(output,'w') as archive:
        archive.writestr('project-main/README','# 示例项目\n公开说明')
        archive.writestr('project-main/pom.xml','<project/>')
        archive.writestr('project-main/src/App.java','class App {}')
        archive.writestr('project-main/.env','SECRET=do-not-import')
        archive.writestr('project-main/node_modules/vendor.js','ignored')
        archive.writestr('../escape.py','unsafe')
    result=repository_document(output.getvalue(),'https://github.com/a/b','a/b','abc123')
    assert 'Java / Maven' in result['metadata']['techStack']
    assert 'class App {}' in result['content'] and 'SECRET' not in result['content'] and 'unsafe' not in result['content']
    assert result['metadata']['skippedFiles']==3


def test_pdf_export_contains_unicode_and_is_readable():
    from pypdf import PdfReader
    data=export_pdf('知识导出','# Python 中文知识\n\n原文与来源。\n\n```\nprint(1)\n```')
    reader=PdfReader(io.BytesIO(data))
    assert len(reader.pages)>=1 and 'Python' in reader.pages[0].extract_text() and '中文知识' in reader.pages[0].extract_text()


@pytest.mark.asyncio
async def test_rerank_validates_entire_order_without_exposing_credentials(monkeypatch):
    monkeypatch.setenv('DASHSCOPE_API_KEY','fixture')
    def handle(request):
        body=json.loads(request.content);assert body['model']=='gte-rerank-v2'
        return httpx.Response(200,json={'output':{'results':[{'index':1},{'index':0}]}})
    assert await Provider(transport=httpx.MockTransport(handle)).rerank('query',['first','second'])==[1,0]
    def invalid(request):return httpx.Response(200,json={'output':{'results':[{'index':0},{'index':0}]}})
    with pytest.raises(ModelUnavailable):await Provider(transport=httpx.MockTransport(invalid)).rerank('query',['first','second'])


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv('TEST_PGVECTOR_DSN'),reason='Requires test pgvector')
async def test_cross_book_fulltext_and_rerank_are_allowlisted(monkeypatch):
    import psycopg
    monkeypatch.setenv('PGVECTOR_DSN',os.environ['TEST_PGVECTOR_DSN'])
    class Model:
        def __init__(self):self.model=uuid.uuid4().hex;self.reranked=False
        def embedding_spec(self):return {'base_url':'fixture','model':self.model,'dimensions':3}
        def embedding_space(self):return EmbeddingSpace.from_spec(self.embedding_spec())
        async def embeddings(self,texts):return [[1,0,0] for _ in texts]
        async def rerank(self,query,documents):self.reranked=True;assert all('秘密' not in doc for doc in documents);return list(reversed(range(len(documents))))
    provider=Model();store=BookVectorStore(provider);first=9_100_000_000_000+uuid.uuid4().int%1_000_000_000;ids=[first,first+1,first+2]
    try:
        for id,body in zip(ids,['蓝莓方案使用锁','蓝莓方案使用队列','蓝莓秘密资料']):
            request=BookIndexRequest(book_id=id,revision=1,title='方案',content=body)
            await store.upsert(request,split_sections(id,'方案','body','Book 正文',None,[Section(body)]))
        scopes=[Scope(book_id=id,revision=1,title='方案') for id in ids[:2]]
        result=await search(KnowledgeRequest(question='蓝莓',scopes=scopes,search_mode='fulltext',rerank=True),provider)
        assert provider.reranked and result['rerankMode']=='gte-rerank-v2'
        assert {item['bookId'] for item in result['results']}==set(ids[:2]) and all('秘密' not in item['excerpt'] for item in result['results'])
        await store.delete(first,2)
        with pytest.raises(IndexNotReady):await search(KnowledgeRequest(question='蓝莓',scopes=scopes),provider)
    finally:
        async with await psycopg.AsyncConnection.connect(os.environ['TEST_PGVECTOR_DSN']) as connection:
            await connection.execute(f'DROP TABLE IF EXISTS {store.table}')
            await connection.execute(f'DROP TABLE IF EXISTS {store.registry}')
            await connection.execute('DELETE FROM book_agent_state WHERE book_id=ANY(%s)',(ids,))


@pytest.mark.asyncio
async def test_semantic_chunks_never_merge_sources_or_pages(monkeypatch):
    from app import semantic
    class Model:
        async def embeddings(self,texts):return [[1,0,0] for _ in texts]
    async def read(request):return {'sections':[{'fileId':3,'sourceName':'资料.pdf','page':1,'text':'第一页知识。'*100},{'fileId':3,'sourceName':'资料.pdf','page':2,'text':'第二页知识。'*100}]}
    monkeypatch.setattr(semantic,'read_book',read)
    result=await semantic.semantic_nodes(BookIndexRequest(book_id=4,revision=1,title='主题',content='在线正文。'*100),Model())
    assert {n['page_no'] for n in result}=={None,1,2}
    assert all(not ('第一页' in n['text'] and '第二页' in n['text']) for n in result)
    assert len({n['chunk_id'] for n in result})==len(result)

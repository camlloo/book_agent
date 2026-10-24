"""Explicit small live smoke test; never run as part of the default test suite."""
import asyncio
import os
import httpx
from app.provider import Provider

async def main():
    provider = Provider()
    channel = provider.config["channels"][0]
    async with httpx.AsyncClient(timeout=60) as client:
        response = await client.post(channel["base_url"] + "/chat/completions",
            headers={"Authorization": "Bearer " + os.getenv(channel["api_key_env"], "")},
            json={"model": channel["model"], "messages": [{"role":"user","content":"只回复：连接成功。"}],
                  "max_tokens":32, **channel.get("extra_body", {})})
        if response.status_code != 200:
            error = response.json().get("error", {})
            print({"chat_status":response.status_code,"code":error.get("code"),"message":str(error.get("message", ""))[:250]})
            vectors = await provider.embeddings(["这是 Book RAG 的向量连接测试。"])
            print({"embedding_model":provider.embedding_spec()["model"],"dimensions":len(vectors[0])})
            return
    reply = response.json()["choices"][0]["message"]["content"]
    vectors = await provider.embeddings(["这是 Book RAG 的向量连接测试。"])
    print({"chat_model": provider.config["channels"][0]["model"], "reply": reply,
           "embedding_model": provider.embedding_spec()["model"], "dimensions": len(vectors[0])})

if __name__ == "__main__":
    asyncio.run(main())

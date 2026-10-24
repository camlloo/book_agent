import asyncio
import os
from pathlib import Path
from app.provider import Provider

for line in (Path(__file__).resolve().parents[2] / ".env").read_text(encoding="utf-8-sig").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        os.environ.setdefault(key, value)

async def main():
    provider = Provider()
    vectors = await provider.embeddings(["Book 正文和上传文件统一向量化。"])
    print({"embedding_model": provider.embedding_spec()["model"], "dimensions": len(vectors[0]), "ready": True})

asyncio.run(main())

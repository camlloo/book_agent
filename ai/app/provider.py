import asyncio
import hashlib
import json
import logging
import os
import threading
from pathlib import Path
from dataclasses import dataclass

import httpx
import yaml
from .context import ContextOverflow, fit_messages

logger = logging.getLogger(__name__)
_local_lock = threading.Lock()
_local_models = {}


class ModelUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class EmbeddingSpace:
    """Resolved vector-space identity, without credentials or transport details."""
    base_url: str
    model: str
    dimensions: int

    @classmethod
    def from_spec(cls, spec: dict) -> "EmbeddingSpace":
        dimensions = int(spec["dimensions"])
        if not 1 <= dimensions <= 2000:
            raise ModelUnavailable("Embedding dimensions must be 1..2000")
        return cls(spec["base_url"], spec["model"], dimensions)

    @property
    def identity(self) -> str:
        # Preserve existing Book namespaces and their stored vectors.
        return hashlib.sha256((self.base_url + self.model + str(self.dimensions)).encode()).hexdigest()[:12]


class Provider:
    """OpenAI-compatible channels with explicit enablement, bounded retries and fallback."""

    def __init__(self, config_path: Path | None = None, transport=None):
        path = config_path or Path(os.environ.get("AI_CONFIG", Path(__file__).parents[1] / "config.yaml"))
        self.config = yaml.safe_load(path.read_text(encoding="utf-8"))
        self.transport = transport

    async def complete(self, task: str, system: str, user: str, history=None) -> str:
        overflow = False
        truncated = False
        for channel in self.config.get("channels", []):
            route = "ASK" if task in ("MEMORY", "REWRITE") else task
            if route not in channel.get("tasks", []):
                continue
            enabled_env = channel.get("enabled_env")
            if enabled_env and os.getenv(enabled_env, "").lower() != "true":
                continue
            key = os.getenv(channel["api_key_env"], "")
            if not key and not enabled_env:
                continue
            try:
                _, messages, output_limit, context_stats = fit_messages(system, user, history or [], channel, task)
            except ContextOverflow:
                overflow = True
                logger.warning("Model channel %s context budget exceeded for %s", channel["name"], task)
                continue
            async with httpx.AsyncClient(timeout=65, transport=self.transport) as client:
                for attempt in range(2):
                    try:
                        response = await client.post(
                            channel["base_url"].rstrip("/") + "/chat/completions",
                            headers={"Authorization": f"Bearer {key or 'ollama'}"},
                            json={**channel.get("extra_body", {}), "model": channel["model"], "messages": messages,
                                "temperature": 0.4, "max_tokens": output_limit},
                        )
                        response.raise_for_status()
                        payload = response.json()
                        choice = payload["choices"][0]
                        if choice.get("finish_reason") == "length":
                            truncated = True
                            raise ValueError("truncated output")
                        output = choice["message"]["content"]
                        if not isinstance(output, str) or not output.strip():
                            raise ValueError("empty output")
                        usage = payload.get("usage", {})
                        logger.info("Model usage channel=%s task=%s estimated_input=%s input_budget=%s output_reserve=%s history_dropped=%s prompt_tokens=%s completion_tokens=%s",
                            channel["name"], task, context_stats["estimatedInputTokens"], context_stats["inputBudget"], output_limit,
                            context_stats["historyDropped"], usage.get("prompt_tokens"), usage.get("completion_tokens"))
                        return output.strip()
                    except (httpx.HTTPError, ValueError, KeyError, IndexError):
                        # Do not log response bodies, headers or keys.
                        logger.warning("Model channel %s attempt %s failed", channel["name"], attempt + 1)
                        if attempt == 0:
                            await asyncio.sleep(0.4)
        if truncated:
            raise ModelUnavailable("模型输出达到长度上限，本次未返回完整结果，请按章节处理长文。")
        if overflow:
            raise ModelUnavailable("输入上下文超过可用模型预算，请按章节处理长文或减少知识范围。")
        raise ModelUnavailable("没有可用模型渠道，请配置密钥或启用本地 Ollama。")

    async def complete_stream(self, task: str, system: str, user: str, history=None):
        """Stream answer deltas over the same channel routing as complete().

        Channel fallback only happens before the first delta: once text reached the
        caller, a broken stream must surface as an error instead of restarting on
        another channel and mixing two partial generations.
        """
        overflow = False
        truncated = False
        for channel in self.config.get("channels", []):
            route = "ASK" if task in ("MEMORY", "REWRITE") else task
            if route not in channel.get("tasks", []):
                continue
            enabled_env = channel.get("enabled_env")
            if enabled_env and os.getenv(enabled_env, "").lower() != "true":
                continue
            key = os.getenv(channel["api_key_env"], "")
            if not key and not enabled_env:
                continue
            try:
                _, messages, output_limit, context_stats = fit_messages(system, user, history or [], channel, task)
            except ContextOverflow:
                overflow = True
                logger.warning("Model channel %s context budget exceeded for %s", channel["name"], task)
                continue
            emitted = 0
            async with httpx.AsyncClient(timeout=65, transport=self.transport) as client:
                for attempt in range(2):
                    try:
                        async with client.stream("POST",
                            channel["base_url"].rstrip("/") + "/chat/completions",
                            headers={"Authorization": f"Bearer {key or 'ollama'}"},
                            json={**channel.get("extra_body", {}), "model": channel["model"], "messages": messages,
                                "temperature": 0.4, "max_tokens": output_limit, "stream": True}) as response:
                            response.raise_for_status()
                            async for line in response.aiter_lines():
                                if not line.startswith("data:"):
                                    continue
                                data = line[5:].strip()
                                if data == "[DONE]":
                                    break
                                try:
                                    payload = json.loads(data)
                                except ValueError:
                                    continue
                                choices = payload.get("choices") or []
                                choice = choices[0] if choices else {}
                                content = (choice.get("delta") or {}).get("content")
                                if content:
                                    emitted += len(content)
                                    yield content
                                if choice.get("finish_reason") == "length":
                                    truncated = True
                                    raise ValueError("truncated output")
                        if not emitted:
                            raise ValueError("empty output")
                        logger.info("Model stream channel=%s task=%s estimated_input=%s input_budget=%s output_reserve=%s history_dropped=%s output_chars=%s",
                            channel["name"], task, context_stats["estimatedInputTokens"], context_stats["inputBudget"], output_limit,
                            context_stats["historyDropped"], emitted)
                        return
                    except (httpx.HTTPError, ValueError, KeyError, IndexError):
                        logger.warning("Model stream channel %s attempt %s failed", channel["name"], attempt + 1)
                        if emitted:
                            if truncated:
                                raise ModelUnavailable("模型输出达到长度上限，本次未返回完整结果，请按章节处理长文。") from None
                            raise ModelUnavailable("回答流中断，请重新提问。") from None
                        if attempt == 0:
                            await asyncio.sleep(0.4)
        if truncated:
            raise ModelUnavailable("模型输出达到长度上限，本次未返回完整结果，请按章节处理长文。")
        if overflow:
            raise ModelUnavailable("输入上下文超过可用模型预算，请按章节处理长文或减少知识范围。")
        raise ModelUnavailable("没有可用模型渠道，请配置密钥或启用本地 Ollama。")

    def embedding_spec(self) -> dict:
        if os.getenv("EMBEDDING_PROVIDER", "remote") == "local":
            model = os.getenv("LOCAL_EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5")
            return {"base_url": "local", "key": "", "model": model, "dimensions": 512, "local": True}
        cfg = self.config.get("embedding", {})
        base = os.getenv(cfg.get("base_url_env", ""), "") or cfg.get("default_base_url", "")
        key = os.getenv(cfg.get("api_key_env", ""), "") or os.getenv(cfg.get("default_api_key_env", ""), "")
        model = os.getenv(cfg.get("model_env", ""), "") or cfg.get("default_model", "")
        dimensions = int(os.getenv(cfg.get("dimensions_env", ""), "") or cfg.get("default_dimensions", 1024))
        if not (base and key and model):
            raise ModelUnavailable("Embedding is not configured")
        if not 1 <= dimensions <= 2000:
            raise ModelUnavailable("Embedding dimensions must be 1..2000")
        return {"base_url": base.rstrip("/"), "key": key, "model": model, "dimensions": dimensions}

    def embedding_space(self) -> EmbeddingSpace:
        return EmbeddingSpace.from_spec(self.embedding_spec())

    async def embeddings(self, texts: list[str]) -> list[list[float]]:
        cfg = self.embedding_spec()
        if cfg.get("local"):
            def encode():
                from fastembed import TextEmbedding
                with _local_lock:
                    if cfg["model"] not in _local_models:
                        _local_models[cfg["model"]] = TextEmbedding(model_name=cfg["model"],
                            cache_dir=str(Path.home() / ".cache" / "book-agent-embeddings"),
                            providers=["CPUExecutionProvider"])
                    return [vector.tolist() for vector in _local_models[cfg["model"]].embed(texts, batch_size=16)]
            return await asyncio.to_thread(encode)
        vectors = []
        async with httpx.AsyncClient(timeout=60, transport=self.transport) as client:
            # DashScope supports at most ten inputs per compatible embeddings request.
            for offset in range(0, len(texts), 10):
                batch = texts[offset:offset + 10]
                for attempt in range(2):
                    try:
                        response = await client.post(cfg["base_url"] + "/embeddings",
                            headers={"Authorization": f"Bearer {cfg['key']}"},
                            json={"model": cfg["model"], "input": batch, "dimensions": cfg["dimensions"]})
                        response.raise_for_status()
                        data = sorted(response.json()["data"], key=lambda item: item["index"])
                        if len(data) != len(batch):
                            raise ValueError("Embedding result count mismatch")
                        result = [item["embedding"] for item in data]
                        if any(len(vector) != cfg["dimensions"] for vector in result):
                            raise ValueError("Embedding dimension mismatch")
                        vectors.extend(result)
                        break
                    except (httpx.HTTPError, ValueError, KeyError):
                        if attempt:
                            raise ModelUnavailable("向量模型调用失败，请检查模型配置、网络与额度。") from None
                        await asyncio.sleep(0.4)
        return vectors


    async def rerank(self, query: str, documents: list[str]) -> list[int]:
        key = os.getenv("DASHSCOPE_API_KEY", "") or os.getenv("QWEN_API_KEY", "")
        if not key or os.getenv("RERANK_ENABLED", "true").lower() != "true":
            raise ModelUnavailable("Rerank 未启用")
        try:
            async with httpx.AsyncClient(timeout=35, transport=self.transport) as client:
                response = await client.post("https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
                    headers={"Authorization": f"Bearer {key}"},
                    json={"model":"gte-rerank-v2","input":{"query":query,"documents":documents},"parameters":{"top_n":len(documents),"return_documents":False}})
                response.raise_for_status()
                order = [item["index"] for item in response.json()["output"]["results"]]
                if any(type(index) is not int for index in order) or sorted(order) != list(range(len(documents))): raise ValueError()
                return order
        except (httpx.HTTPError,ValueError,KeyError,TypeError):
            raise ModelUnavailable("重排服务暂不可用，已保留融合排序") from None

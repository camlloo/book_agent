"""Provision isolated local pgvector resources; never print database credentials."""
import os
import json
import secrets
import subprocess
from pathlib import Path
import psycopg
from psycopg import sql
from psycopg.conninfo import make_conninfo

root = Path(__file__).resolve().parents[2]
env_file = root / ".env"
lines = env_file.read_text(encoding="utf-8-sig").splitlines() if env_file.exists() else []
values = dict(line.split("=", 1) for line in lines if "=" in line and not line.lstrip().startswith("#"))

def save_value(key, value):
    global lines
    lines = [line for line in lines if not line.startswith(key + "=")]
    lines.append(key + "=" + value)

try:
    if not (values.get("PGVECTOR_DSN") or os.getenv("PGVECTOR_DSN")):
        container = json.loads(subprocess.check_output(["docker", "inspect", "pgvector"]))[0]
        container_env = dict(item.split("=", 1) for item in container["Config"]["Env"] if "=" in item)
        admin = container_env.get("POSTGRES_USER", "postgres")
        dsn = make_conninfo(host="127.0.0.1", port=5433, user=admin,
            password=container_env.get("POSTGRES_PASSWORD", ""), dbname=container_env.get("POSTGRES_DB", admin))
        name = "book_agent_rag_" + secrets.token_hex(4)
        password = secrets.token_urlsafe(32)
        with psycopg.connect(dsn, autocommit=True) as connection:
            connection.execute(sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(sql.Identifier(name), sql.Literal(password)))
            connection.execute(sql.SQL("CREATE DATABASE {} OWNER {}").format(sql.Identifier(name), sql.Identifier(name)))
        with psycopg.connect(make_conninfo(dsn, dbname=name), autocommit=True) as connection:
            connection.execute("CREATE EXTENSION IF NOT EXISTS vector")
        save_value("PGVECTOR_DSN", make_conninfo(host="127.0.0.1", port=5433, user=name, password=password, dbname=name))
        print("Created isolated Book RAG database and application role in the existing pgvector container.")
    if not values.get("EMBEDDING_PROVIDER") and not os.getenv("EMBEDDING_PROVIDER"):
        save_value("EMBEDDING_PROVIDER", "local")
    env_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("RAG configuration saved locally; credentials were not printed.")
except Exception as error:
    print("RAG setup failed (" + type(error).__name__ + "). Check Docker and local pgvector access.")
    raise SystemExit(1) from None

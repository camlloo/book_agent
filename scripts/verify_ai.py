"""Run deterministic AI tests, optionally including the configured real pgvector DB."""
import argparse
import os
from pathlib import Path
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-vector-db", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.with_vector_db:
        config = root / ".env"
        if config.exists():
            for line in config.read_text(encoding="utf-8-sig").splitlines():
                if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    os.environ.setdefault(key.strip(), value.strip())
        dsn = os.getenv("TEST_PGVECTOR_DSN") or os.getenv("PGVECTOR_DSN")
        if not dsn:
            parser.error("Configure TEST_PGVECTOR_DSN or PGVECTOR_DSN for vector integration tests")
        os.environ["TEST_PGVECTOR_DSN"] = dsn
    os.chdir(root / "ai")
    sys.path.insert(0, str(root / "ai"))
    import pytest
    return pytest.main(["-q", "--tb=short"])


if __name__ == "__main__":
    raise SystemExit(main())

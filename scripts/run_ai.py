"""WSL launcher: reads the same local .env as Java; secrets never appear in argv."""
import os
from pathlib import Path
import sys
import logging

root = Path(__file__).resolve().parents[1]
config = root / ".env"
if config.exists():
    for line in config.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())
os.chdir(root / "ai")
sys.path.insert(0, str(root / "ai"))
import uvicorn
# Emit only application context usage; do not enable httpx URL logs (signed URLs).
usage_logger = logging.getLogger("app.provider")
usage_logger.setLevel(logging.INFO)
usage_logger.addHandler(logging.StreamHandler())
uvicorn.run("app.main:app", host="127.0.0.1", port=int(os.getenv("AI_PORT", "8100")))

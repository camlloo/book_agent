$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Load-Env.ps1')
$linuxRoot = (wsl -e wslpath -a $projectRoot.Replace('\', '/')).Trim()
wsl -e bash -lc "cd '$linuxRoot/ai' && UV_PROJECT_ENVIRONMENT=~/book-agent-venv ~/.local/bin/uv sync --locked && ~/book-agent-venv/bin/python scripts/setup_rag.py"
if ($LASTEXITCODE -ne 0) { throw 'RAG setup failed. No credentials were printed.' }

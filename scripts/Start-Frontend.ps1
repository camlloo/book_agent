$ErrorActionPreference = 'Stop'
Push-Location (Join-Path (Split-Path $PSScriptRoot -Parent) 'frontend')
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) { npm ci }
    npm run dev
} finally { Pop-Location }

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Load-Env.ps1')
# Bridge the credential in process environment, never in argv or a project file.
$bridgeNames = @('DASHSCOPE_API_KEY', 'GITHUB_TOKEN', 'GITLAB_TOKEN')
$wslEntries = @($env:WSLENV -split ':' | Where-Object { $_ -and ($_.Split('/')[0] -notin $bridgeNames) })
$env:WSLENV = ($wslEntries + @($bridgeNames | ForEach-Object { "$_/u" })) -join ':' 
$linuxRoot = (wsl -e wslpath -a $projectRoot.Replace('\', '/')).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot convert the project path to WSL.' }
# Environment stays in WSL home, not on the Windows-mounted filesystem.
wsl -e bash -lc "cd '$linuxRoot/ai' && UV_PROJECT_ENVIRONMENT=~/book-agent-venv ~/.local/bin/uv sync --locked --python /usr/bin/python3 && ~/book-agent-venv/bin/python '$linuxRoot/scripts/run_ai.py'"

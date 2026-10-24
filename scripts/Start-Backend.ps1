$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Load-Env.ps1')
Push-Location (Join-Path $projectRoot 'backend')
try { mvn spring-boot:run } finally { Pop-Location }

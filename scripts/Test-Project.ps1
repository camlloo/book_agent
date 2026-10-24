param(
    [switch]$WithVectorDatabase,
    [string]$LogDirectory = (Join-Path $env:TEMP ('Book_Agent-tests-' + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
$script:failedChecks = 0

function Invoke-ProjectCheck {
    param([string]$Name, [string]$Directory, [string]$Executable, [string[]]$CommandArgs)
    $logPath = Join-Path $LogDirectory ($Name + '.log')
    Push-Location $Directory
    try {
        & $Executable @CommandArgs *> $logPath
        $checkExit = $LASTEXITCODE
        if ($checkExit -ne 0) {
            $script:failedChecks++
            Write-Host "FAIL $Name ($checkExit): $logPath"
            Get-Content -LiteralPath $logPath -Tail 25 | Write-Host
        } else { Write-Host "PASS $Name : $logPath" }
    } finally { Pop-Location }
}

# Tests use their isolated H2 data and uniquely named pgvector tables.
# This script neither restarts services nor modifies application configuration.
$maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$wsl = (Get-Command wsl.exe -ErrorAction Stop).Source
$linuxRoot = (& $wsl -e wslpath -a $projectRoot.Replace('\', '/')).Trim()
if ($LASTEXITCODE -ne 0 -or -not $linuxRoot) { throw 'Cannot resolve the project directory in WSL.' }
$quotedRoot = "'" + $linuxRoot.Replace("'", "'\''") + "'"
$aiCommand = "~/book-agent-venv/bin/python $quotedRoot/scripts/verify_ai.py"
if ($WithVectorDatabase) { $aiCommand += ' --with-vector-db' }

Invoke-ProjectCheck -Name 'backend-package-tests' -Directory (Join-Path $projectRoot 'backend') -Executable $maven -CommandArgs @('-q', 'package')
Invoke-ProjectCheck -Name 'ai-tests' -Directory $projectRoot -Executable $wsl -CommandArgs @('-e', 'bash', '-lc', $aiCommand)
Invoke-ProjectCheck -Name 'frontend-tests' -Directory (Join-Path $projectRoot 'frontend') -Executable $npm -CommandArgs @('test')
Invoke-ProjectCheck -Name 'frontend-build' -Directory (Join-Path $projectRoot 'frontend') -Executable $npm -CommandArgs @('run', 'build')
Write-Host "Logs: $LogDirectory"
if ($script:failedChecks -gt 0) { exit 1 }

$projectRoot = Split-Path $PSScriptRoot -Parent
# User-scoped Windows credentials may have been added after this process started.
$userDashScopeKey = [Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY', 'User')
if (-not [string]::IsNullOrWhiteSpace($userDashScopeKey)) { $env:DASHSCOPE_API_KEY = $userDashScopeKey }
$configFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $configFile)) {
    $randomBytes = [byte[]]::new(32)
    $randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    $randomGenerator.GetBytes($randomBytes)
    $randomGenerator.Dispose()
    $generatedToken = [BitConverter]::ToString($randomBytes).Replace('-', '').ToLowerInvariant()
    [IO.File]::WriteAllText($configFile, "INTERNAL_TOKEN=$generatedToken`n", [Text.UTF8Encoding]::new($false))
    Write-Host 'Created .env with a random internal service token.'
}
foreach ($line in [IO.File]::ReadAllLines($configFile)) {
    if ($line.Trim() -and -not $line.Trim().StartsWith('#') -and $line.Contains('=')) {
        $pair = $line.Split('=', 2)
        [Environment]::SetEnvironmentVariable($pair[0].Trim(), $pair[1].Trim(), 'Process')
    }
}
# The user's refreshed credential takes priority over project/process leftovers.
if (-not [string]::IsNullOrWhiteSpace($userDashScopeKey)) { $env:DASHSCOPE_API_KEY = $userDashScopeKey }
if ([string]::IsNullOrWhiteSpace($env:INTERNAL_TOKEN)) {
    $tokenBytes = [byte[]]::new(32)
    $tokenGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    $tokenGenerator.GetBytes($tokenBytes)
    $tokenGenerator.Dispose()
    $newToken = [BitConverter]::ToString($tokenBytes).Replace('-', '').ToLowerInvariant()
    $existingConfig = [IO.File]::ReadAllText($configFile)
    if ($existingConfig -match '(?m)^\s*INTERNAL_TOKEN\s*=') {
        $existingConfig = [Regex]::Replace($existingConfig, '(?m)^\s*INTERNAL_TOKEN\s*=.*$', "INTERNAL_TOKEN=$newToken")
    } else { $existingConfig += "`nINTERNAL_TOKEN=$newToken`n" }
    [IO.File]::WriteAllText($configFile, $existingConfig, [Text.UTF8Encoding]::new($false))
    $env:INTERNAL_TOKEN = $newToken
    Write-Host 'Generated the missing local internal service token.'
}

# Repository credentials, like the model key, prefer refreshed user-scoped settings.
foreach ($credentialName in @('GITHUB_TOKEN', 'GITLAB_TOKEN')) {
    $credentialValue = [Environment]::GetEnvironmentVariable($credentialName, 'User')
    if (-not [string]::IsNullOrWhiteSpace($credentialValue)) {
        [Environment]::SetEnvironmentVariable($credentialName, $credentialValue, 'Process')
    }
}

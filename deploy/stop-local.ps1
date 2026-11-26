[CmdletBinding()]
param([switch]$StopMiddleware)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
$ProjectRoot = Split-Path $PSScriptRoot -Parent
$RunRoot = Join-Path $ProjectRoot 'logs/local-stack'
$StatePath = Join-Path $RunRoot 'processes.json'
$Lock = $null
try {
    if (Test-Path $RunRoot) {
        $Lock = [IO.File]::Open((Join-Path $RunRoot 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
        Stop-OwnedProcesses @(Read-LocalState $StatePath)
        Save-LocalState $StatePath @()
    }
    if ($StopMiddleware) {
        & docker.exe compose -f (Join-Path $PSScriptRoot 'docker-compose.middleware.yml') stop mysql redis rabbitmq nacos elasticsearch
        if ($LASTEXITCODE -ne 0) { throw 'Docker middleware stop failed.' }
    }
    Write-Host 'Stopped launcher-owned services. Database volumes are preserved.' -ForegroundColor Green
    if (-not $StopMiddleware) { Write-Host 'Docker middleware is still running. Use -StopMiddleware to stop it too.' }
} catch {
    Write-Host "STOP FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    if ($null -ne $Lock) { $Lock.Dispose() }
}

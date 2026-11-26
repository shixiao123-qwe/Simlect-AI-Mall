param(
    [ValidateSet('web', 'admin')]
    [string]$Target = 'web'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path $PSScriptRoot -Parent
$FrontendRoot = Join-Path $ProjectRoot "Simlect-front/Simlect-$Target"
if (-not (Test-Path (Join-Path $FrontendRoot 'node_modules/.bin/vite.cmd'))) {
    throw "Dependencies missing. Run npm.cmd ci in $FrontendRoot first."
}

$PreviousNodeOptions = $env:NODE_OPTIONS
$PreviousRayonThreads = $env:RAYON_NUM_THREADS
Push-Location $FrontendRoot
try {
    # Bound JS heap and Rust/Rolldown worker parallelism independently.
    $env:NODE_OPTIONS = '--max-old-space-size=512'
    $env:RAYON_NUM_THREADS = '2'
    & npm.cmd run dev
    if ($LASTEXITCODE -ne 0) { throw "Frontend exited with code $LASTEXITCODE" }
} finally {
    $env:NODE_OPTIONS = $PreviousNodeOptions
    $env:RAYON_NUM_THREADS = $PreviousRayonThreads
    Pop-Location
}

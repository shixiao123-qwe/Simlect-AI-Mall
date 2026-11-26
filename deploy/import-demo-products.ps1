[CmdletBinding()]
param([switch]$SyncSearch)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$config=Read-LocalEnv (Join-Path $PSScriptRoot '.env.local')
$previousEncoding=$OutputEncoding
$previousPassword=$env:MYSQL_PWD
try {
    $OutputEncoding=New-Object Text.UTF8Encoding($false)
    $env:MYSQL_PWD=$config['MYSQL_PASSWORD']
    if (-not $env:MYSQL_PWD) { throw 'MYSQL_PASSWORD is missing from deploy/.env.local' }
    $user=$config['MYSQL_USER']
    if (-not $user) { $user='root' }
    Get-Content -LiteralPath (Join-Path $root 'sql/21_demo_ai_products.sql') -Raw -Encoding UTF8 |
        & docker.exe exec -i -e MYSQL_PWD simlect-mysql mysql "-u$user" --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) { throw 'Demo import failed. See MySQL output above.' }
    Write-Host 'Demo rows imported; existing primary keys were preserved.' -ForegroundColor Green
    Write-Host 'After a direct SQL import, restart Product once to refresh its startup Bloom filter before opening product details.' -ForegroundColor Yellow
    if ($SyncSearch) {
        $token=$config['SIMLECT_INTERNAL_TOKEN']
        if (-not $token) { throw 'SIMLECT_INTERNAL_TOKEN missing; SQL import succeeded but search sync was not run.' }
        # Local maintenance call: Search owns this endpoint, and older gateways lack its route.
        $r=Invoke-WebRequest -Uri 'http://127.0.0.1:8108/internal/search/tool/productData' -Method Post -Headers @{'X-Internal-Token'=$token} -UseBasicParsing -TimeoutSec 120
        $body=$r.Content
        if ($body -is [byte[]]) { $body=[Text.Encoding]::UTF8.GetString($body) }
        $data=ConvertFrom-Json $body
        if ($data.status -ne 'success') { throw 'SQL imported but search sync failed; check search.out.log.' }
        Write-Host 'On-sale product search indexes synchronized. Refresh the storefront.' -ForegroundColor Green
    } else {
        Write-Host 'Use -SyncSearch with the backend running to synchronize keyword search.'
    }
} finally {
    $OutputEncoding=$previousEncoding
    $env:MYSQL_PWD=$previousPassword
}

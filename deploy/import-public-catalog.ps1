[CmdletBinding()]
param([switch]$SyncSearch)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$config=Read-LocalEnv (Join-Path $PSScriptRoot '.env.local')
if($config['SIMLECT_PRODUCTION_READY'] -eq 'true' -or $env:SIMLECT_PRODUCTION_READY -eq 'true'){throw 'This sample importer is local-only.'}
$manifest=Get-Content (Join-Path $root 'data/public-catalog/manifest.json') -Raw -Encoding UTF8|ConvertFrom-Json
$sql=Join-Path $root 'sql/24_public_catalog.sql'
if($manifest.products -ne 50 -or (Get-FileHash -LiteralPath $sql -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sqlSha256){throw 'Sample manifest/SQL mismatch; regenerate before importing.'}
$rows=@(Get-Content (Join-Path $root 'data/public-catalog/products.json') -Raw -Encoding UTF8|ConvertFrom-Json)
foreach($r in $rows){if($r.productId -notmatch '^ABO[A-Z0-9]{10}$'){throw 'Unexpected sample product ID'};if(-not(Test-Path -LiteralPath (Join-Path $config['PROJECT_FOLDER'] ('file/public-catalog/'+$r.productId+'.jpg')))){throw 'Missing local sample image; run preparation first.'}}
$state=Join-Path $root 'logs/local-stack/processes.json'
$previousEncoding=$OutputEncoding;$previousPassword=$env:MYSQL_PWD;$lock=$null
try{
    $lock=[IO.File]::Open((Join-Path $root 'logs/local-stack/launcher.lock'),'OpenOrCreate','ReadWrite','None')
    $entry=@(Read-LocalState $state|Where-Object Name -eq 'product')
    if($entry.Count -gt 1){throw 'Duplicate Product registry entries'}
    $owned=if($entry.Count){Get-OwnedProcess $entry[0]}else{$null}
    if(-not $owned -and (Test-LocalPort 8099)){throw 'Product port belongs to an unverified process; no import or restart performed.'}
    $OutputEncoding=New-Object Text.UTF8Encoding($false);$env:MYSQL_PWD=$config['MYSQL_PASSWORD']
    if(-not $env:MYSQL_PWD){throw 'Local MYSQL_PASSWORD missing'}
    $user=$config['MYSQL_USER'];if(-not $user){$user='root'}
    Get-Content -LiteralPath $sql -Raw -Encoding UTF8|& docker.exe exec -i -e MYSQL_PWD simlect-mysql mysql "-u$user" --default-character-set=utf8mb4
    if($LASTEXITCODE -ne 0){throw 'Sample import failed; no restart attempted.'}
    $curation=Join-Path $root 'sql/25_public_catalog_curation.sql'
    if($manifest.curationSqlSha256){
        if((Get-FileHash -LiteralPath $curation -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.curationSqlSha256){throw 'Curation migration hash mismatch'}
        Get-Content -LiteralPath $curation -Raw -Encoding UTF8|& docker.exe exec -i -e MYSQL_PWD simlect-mysql mysql "-u$user" --default-character-set=utf8mb4
        if($LASTEXITCODE -ne 0){throw 'Curation migration failed'}
    }
    # Direct SQL import bypasses category cache maintenance.
    & docker.exe exec simlect-redis redis-cli DEL 'mall:category:list:' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Import succeeded but category cache refresh failed.'}
    Write-Host '50 public sample products imported (existing primary keys kept; no order/payment tables touched).'
    if($owned){Stop-OwnedProcesses $entry}
}finally{if($lock){$lock.Dispose()};$OutputEncoding=$previousEncoding;$env:MYSQL_PWD=$previousPassword}
# Direct SQL import must refresh Product startup Bloom filter before opening details.
& (Join-Path $PSScriptRoot 'repair-local-services.ps1') -Services product
if($SyncSearch){
    $r=Invoke-RestMethod -Uri 'http://127.0.0.1:8108/internal/search/tool/productData' -Method Post -Headers @{'X-Internal-Token'=$config['SIMLECT_INTERNAL_TOKEN']} -TimeoutSec 120
    if($r.code -ne 200){throw 'Database import succeeded, but search synchronization failed.'}
    Write-Host 'Search synchronized. Refresh the storefront.'
}

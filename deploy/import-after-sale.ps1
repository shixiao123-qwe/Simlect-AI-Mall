[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
$config=Read-LocalEnv (Join-Path $PSScriptRoot '.env.local')
$root=Split-Path $PSScriptRoot -Parent
$previousEncoding=$OutputEncoding
$previousPassword=$env:MYSQL_PWD
try {
    $OutputEncoding=New-Object Text.UTF8Encoding($false)
    $env:MYSQL_PWD=$config['MYSQL_PASSWORD']
    if (-not $env:MYSQL_PWD) { throw 'MYSQL_PASSWORD missing in deploy/.env.local' }
    $user=$config['MYSQL_USER']
    if (-not $user) { $user='root' }
    foreach ($migration in @('sql/22_after_sale.sql','sql/23_after_sale_fulfillment.sql')) {
        Get-Content -LiteralPath (Join-Path $root $migration) -Raw -Encoding UTF8 |
            & docker.exe exec -i -e MYSQL_PWD simlect-mysql mysql "-u$user" --default-character-set=utf8mb4
        if ($LASTEXITCODE -ne 0) { throw "After-sale schema migration failed: $migration" }
    }
    Write-Host 'After-sale tables are ready. Existing orders and payments were preserved.'
} finally {
    $OutputEncoding=$previousEncoding
    $env:MYSQL_PWD=$previousPassword
}

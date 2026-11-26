[CmdletBinding()]
param([int]$Samples=12,[int]$IntervalSeconds=5,[string]$OutputPath='')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
if($Samples -lt 1 -or $Samples -gt 720 -or $IntervalSeconds -lt 1 -or $IntervalSeconds -gt 60){throw 'Sampling limits: 1..720 samples, 1..60 seconds interval'}
$rows=@()
for($i=0;$i -lt $Samples;$i++){
    $m=Get-LocalMemoryBudget
    $rows+=[pscustomobject]@{Time=(Get-Date -Format o);PhysicalFreeMB=$m.PhysicalFreeMB;CommitFreeMB=$m.CommitFreeMB;TargetOK=($m.PhysicalFreeMB -ge 4096);ReserveOK=($m.PhysicalFreeMB -ge 1024 -and $m.CommitFreeMB -ge 2048)}
    if($i -lt $Samples-1){Start-Sleep -Seconds $IntervalSeconds}
}
$report=[pscustomobject]@{TargetPhysicalMB=4096;HardPhysicalMB=1024;TargetOK=(@($rows|Where-Object{-not $_.TargetOK}).Count -eq 0);Samples=$Samples;MinPhysicalFreeMB=($rows.PhysicalFreeMB|Measure-Object -Minimum).Minimum;MinCommitFreeMB=($rows.CommitFreeMB|Measure-Object -Minimum).Minimum;ReserveOK=(@($rows|Where-Object{-not $_.ReserveOK}).Count -eq 0);Rows=$rows}
if($OutputPath){$report|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $OutputPath -Encoding UTF8}
$report|Select-Object Samples,MinPhysicalFreeMB,MinCommitFreeMB,ReserveOK,TargetOK
if(-not $report.ReserveOK){exit 1}
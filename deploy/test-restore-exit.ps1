$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$folder=Join-Path $root 'logs/restore-handle-test'
New-Item -ItemType Directory -Force $folder|Out-Null
foreach($expected in @(7,0)){
 $p=Start-Process powershell.exe -ArgumentList @('-NoProfile','-Command',"exit $expected") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $folder "$expected.out.log") -RedirectStandardError (Join-Path $folder "$expected.err.log")
 $null=$p.Handle
 if(-not $p.WaitForExit(10000)){throw 'Wait timeout'}
 if($null -eq $p.ExitCode -or $p.ExitCode -ne $expected){throw "Exit code mismatch: expected $expected, got $($p.ExitCode)"}
 Write-Host "Handle/exit test passed: $expected"
}

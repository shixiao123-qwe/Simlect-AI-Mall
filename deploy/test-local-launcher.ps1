# Offline regression checks. Does not touch Docker, databases, APIs, or running app services.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
function Assert-Local($Condition, [string]$Message) {
    if (-not $Condition) { throw "ASSERT FAILED: $Message" }
}
$TestRoot = Join-Path (Split-Path $PSScriptRoot -Parent) ('logs/launcher-tests/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $TestRoot -Force | Out-Null
$TestProcess = $null
try {
    foreach ($name in @('local-stack-common.ps1','start-local.ps1','stop-local.ps1','test-local-memory-safe.ps1','repair-local-services.ps1','watch-local-memory.ps1')) {
        $tokens = $null
        $parseErrors = $null
        [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$parseErrors) | Out-Null
        Assert-Local ($parseErrors.Count -eq 0) "PowerShell parser: $name"
    }
    $envPath = Join-Path $TestRoot 'literal.env'
    [IO.File]::WriteAllText($envPath, @'
# comment
TEST_ONE=literal$();abc=123
TEST_TWO="space value"
TEST_EMPTY=
'@)
    $values = Read-LocalEnv $envPath
    Assert-Local ($values.TEST_ONE -ceq 'literal$();abc=123') 'Env values must remain literal'
    Assert-Local ($values.TEST_TWO -ceq 'space value') 'Quoted value'
    Assert-Local ($values.TEST_EMPTY -eq '') 'Empty value'
    [IO.File]::WriteAllText($envPath, 'not an assignment')
    $rejected = $false
    try { Read-LocalEnv $envPath | Out-Null } catch { $rejected = $true }
    Assert-Local $rejected 'Invalid env line must be rejected'

    $plan = @(Get-LocalServicePlan)
    Assert-Local ($plan.Count -eq 10) 'Exactly ten Java services'
    Assert-Local (@($plan.Port | Select-Object -Unique).Count -eq 10) 'No duplicate ports'
    Assert-Local (($plan | ForEach-Object { $_.Heap } | Measure-Object -Sum).Sum -eq 2176) 'Expected combined JVM heap limit'
    Assert-Local ((@(Get-LocalServicePlan -Scenario guide).Name -join ',') -eq 'user,product,stock,search,gateway') 'Guide scenario dependency set'
    Assert-Local (@(Get-LocalServicePlan -Scenario after-sale).Count -eq 9) 'After-sale keeps admin and transaction services'
    Assert-Local ((Get-LocalMysqlPort @{}) -eq 3306) 'Default database port remains compatible'
    Assert-Local ((Get-LocalMysqlPort @{MYSQL_PORT='3307'}) -eq 3307) 'Isolated local MySQL port'
    $badPort=$false;try{Get-LocalMysqlPort @{MYSQL_PORT='65536'}|Out-Null}catch{$badPort=$true}
    Assert-Local $badPort 'Reject invalid local MySQL port'
    Assert-Local ((Get-LocalJdbcUrl product 3307) -like 'jdbc:mysql://127.0.0.1:3307/simlect_product?*') 'Local service-specific database URL'

    # Verify both memory limits independently without exhausting the machine.
    $originalMemoryReader=${function:Get-LocalMemoryBudget}
    try {
        function Get-LocalMemoryBudget { [pscustomobject]@{PhysicalFreeMB=4096;CommitFreeMB=8192} }
        Assert-LocalMemoryBudget 'test-pass' 1536 3072 | Out-Null
        function Get-LocalMemoryBudget { [pscustomobject]@{PhysicalFreeMB=1023;CommitFreeMB=8192} }
        $rejected=$false;try{Assert-LocalMemoryBudget 'test-physical' 1536 3072|Out-Null}catch{$rejected=$true}
        Assert-Local $rejected 'Reject low physical memory before starting a workload'
        function Get-LocalMemoryBudget { [pscustomobject]@{PhysicalFreeMB=1024;CommitFreeMB=8192} }
        Assert-LocalMemoryBudget 'test-hard-boundary' | Out-Null
        function Get-LocalMemoryBudget { [pscustomobject]@{PhysicalFreeMB=3072;CommitFreeMB=8192} }
        Assert-LocalMemoryBudget 'test-below-target' | Out-Null
        function Get-LocalMemoryBudget { [pscustomobject]@{PhysicalFreeMB=4096;CommitFreeMB=512} }
        $rejected=$false;try{Assert-LocalMemoryBudget 'test-commit' 1536 3072|Out-Null}catch{$rejected=$true}
        Assert-Local $rejected 'Reject low system commit headroom'
    } finally { ${function:Get-LocalMemoryBudget}=$originalMemoryReader }
    # Exercise native Windows argument handling with a real harmless Node child.
    $node = (Get-Command node.exe -CommandType Application | Select-Object -First 1).Source
    $nativeLog=Join-Path $TestRoot 'native-stderr.log'
    $nativeScript=Join-Path $TestRoot 'native.cjs'
    [IO.File]::WriteAllText($nativeScript,'process.stderr.write("expected nonzero stderr");process.exit(7)')
    $nativeExit=Invoke-LocalLoggedCommand $node @($nativeScript) $nativeLog
    Assert-Local ($nativeExit -eq 7) 'Nonzero native exit stays failure'
    [IO.File]::WriteAllText($nativeScript,'process.stderr.write("normal progress on stderr");process.exit(0)')
    $nativeExit=Invoke-LocalLoggedCommand $node @($nativeScript) $nativeLog
    Assert-Local ($nativeExit -eq 0) 'Native progress on stderr must not abort recovery'
    $capture = Join-Path $TestRoot 'capture args.cjs'
    [IO.File]::WriteAllText($capture, 'process.stdout.write(JSON.stringify(process.argv.slice(2))); setInterval(()=>{},1000);')
    $samples = @('plain','path with spaces','C:\path with spaces\','a"b','literal$();token')
    $nativeArgs = (@($capture) + $samples | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $outPath = Join-Path $TestRoot 'child.out.log'
    $TestProcess = Start-Process -FilePath $node -ArgumentList $nativeArgs -PassThru -WindowStyle Hidden -RedirectStandardOutput $outPath -RedirectStandardError (Join-Path $TestRoot 'child.err.log')
    $entry = [pscustomobject]@{Name='test-child'; ProcessId=$TestProcess.Id; StartTicks=$TestProcess.StartTime.ToUniversalTime().Ticks.ToString(); Executable=$node}
    $captured = $null
    for ($i=0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 100
        $content = Get-Content $outPath -Raw
        if ($content) { $captured = [array](ConvertFrom-Json $content); break }
    }
    Assert-Local ($captured.Count -eq $samples.Count) 'Native argument count'
    for ($i=0; $i -lt $samples.Count; $i++) { Assert-Local ($captured[$i] -ceq $samples[$i]) "Native argument $i" }
    $statePath = Join-Path $TestRoot 'state.json'
    Save-LocalState $statePath @($entry)
    $loaded = @(Read-LocalState $statePath)
    Assert-Local ($null -ne (Get-OwnedProcess $loaded[0])) 'Recognize owned process after state roundtrip'
    $loaded[0].StartTicks = '0'
    Save-LocalState $statePath @($entry, $loaded[0])
    Assert-Local (@(Read-LocalState $statePath).Count -eq 2) 'Multiple state entries in Windows PowerShell 5.1'
    Stop-OwnedProcesses $loaded
    $TestProcess.Refresh()
    Assert-Local (-not $TestProcess.HasExited) 'Never kill a reused PID with mismatched start time'
    Stop-OwnedProcesses @($entry)
    Assert-Local ($null -eq (Get-OwnedProcess $entry)) 'Stop only the owned child'
    Save-LocalState $statePath @()
    Assert-Local (@(Read-LocalState $statePath).Count -eq 0) 'Empty state roundtrip'

    # Readiness must inspect JSON status, not just HTTP 200.
    $script:ProbeCount = 0
    function Invoke-WebRequest {
        param($Uri, [switch]$UseBasicParsing, $TimeoutSec)
        $script:ProbeCount++
        if ($script:ProbeCount -eq 1) { return [pscustomobject]@{StatusCode=200; Content='{"status":"degraded"}'} }
        return [pscustomobject]@{StatusCode=200; Content=([Text.Encoding]::UTF8.GetBytes('{"status":"ok"}'))}
    }
    function Start-Sleep { param($Seconds, $Milliseconds) }
    Wait-LocalHttp 'mock-agent' 'http://unused.test' 'ok' $null 5
    Assert-Local ($script:ProbeCount -eq 2) 'Reject degraded health'
    $timedOut = $false
    try { Wait-LocalHttp 'mock-timeout' 'http://unused.test' 'ok' $null 0 } catch { $timedOut = $true }
    Assert-Local $timedOut 'Bound readiness waits'
    Write-Host 'PASS: parser, literal config, service plan, Windows quoting, process ownership, state, health and timeout.' -ForegroundColor Green
    Write-Host "Test artifacts: $TestRoot"
} finally {
    if ($null -ne $TestProcess) {
        $TestProcess.Refresh()
        if (-not $TestProcess.HasExited) { Stop-Process -InputObject $TestProcess -ErrorAction SilentlyContinue }
    }
}






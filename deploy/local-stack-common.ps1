# Shared helpers. Dot-sourcing this file never starts or stops a service.
function Read-LocalEnv([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)) {
        $lineNumber++
        if ($line.Trim() -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            throw "Invalid KEY=value at ${Path}:$lineNumber"
        }
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        # Literal values only: never Invoke-Expression or expand $(), $, or backticks.
        $values[$key] = $value
    }
    return $values
}

function ConvertTo-NativeArgument([string]$Value) {
    # Windows CommandLineToArgvW quoting, including trailing backslashes.
    return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}

function Test-LocalPort([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return ($task.Wait(500) -and $client.Connected)
    } catch { return $false } finally { $client.Dispose() }
}

function Get-LocalServicePlan {
    param([ValidateSet('full','guide','after-sale')][string]$Scenario='full')
    $plan=@(
        @{Name='user'; Module='Simlect-user/app'; Port=8105; Heap=256}
        @{Name='product'; Module='Simlect-product/app'; Port=8099; Heap=256}
        @{Name='stock'; Module='Simlect-stock/app'; Port=8102; Heap=192}
        @{Name='cart'; Module='Simlect-cart/app'; Port=8084; Heap=192}
        @{Name='coupon'; Module='Simlect-coupon/app'; Port=8087; Heap=192}
        @{Name='order'; Module='Simlect-order/app'; Port=8093; Heap=256}
        @{Name='pay'; Module='Simlect-pay/app'; Port=8096; Heap=192}
        @{Name='search'; Module='Simlect-search'; Port=8108; Heap=256}
        @{Name='admin'; Module='Simlect-admin'; Port=8111; Heap=192}
        @{Name='gateway'; Module='Simlect-gateway'; Port=8080; Heap=192}
    )
    if($Scenario -eq 'guide'){return @($plan|Where-Object {$_.Name -in @('user','product','stock','search','gateway')})}
    if($Scenario -eq 'after-sale'){return @($plan|Where-Object Name -ne 'search')}
    return $plan
}

function Read-LocalState([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        $content = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
        if ($content.Trim()) {
            # Windows PowerShell 5.1 emits a JSON array as one object; enumerate explicitly.
            foreach ($entry in (ConvertFrom-Json $content)) { Write-Output $entry }
            return
        }
    }
    return @()
}

function Get-OwnedProcess($Entry) {
    $process = Get-Process -Id $Entry.ProcessId -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        # PID alone is unsafe after reboot: also require exact start time and executable.
        try {
            if ($process.StartTime.ToUniversalTime().Ticks.ToString() -eq $Entry.StartTicks -and $process.Path -eq $Entry.Executable) {
                return $process
            }
        } catch { return $null }
    }
    return $null
}

function Save-LocalState([string]$Path, [array]$Entries) {
    $json = ConvertTo-Json -InputObject @($Entries) -Depth 5
    [IO.File]::WriteAllText($Path, $json, (New-Object Text.UTF8Encoding($false)))
}

function Stop-OwnedProcesses([array]$Entries) {
    $reverse = @($Entries)
    [array]::Reverse($reverse)
    foreach ($entry in $reverse) {
        $process = Get-OwnedProcess $entry
        if ($null -ne $process) {
            Write-Host "Stopping $($entry.Name) (PID $($entry.ProcessId))"
            Stop-Process -InputObject $process -ErrorAction Stop
            if (-not $process.WaitForExit(15000)) { throw "Process did not exit: $($entry.Name)" }
        }
    }
}

function Wait-LocalHttp([string]$Name, [string]$Url, [string]$ExpectedStatus, $Process = $null, [int]$TimeoutSeconds = 180) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $nextNotice = [DateTime]::UtcNow.AddSeconds(20)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -ne $Process) {
            $Process.Refresh()
            if ($Process.HasExited) { throw "$Name exited. Check its .out.log and .err.log files." }
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10
            if ($response.StatusCode -eq 200) {
                if (-not $ExpectedStatus) { return }
                $json = $response.Content
                if ($json -is [byte[]]) { $json = [Text.Encoding]::UTF8.GetString($json) }
                $body = ConvertFrom-Json $json
                if ($body.status -eq $ExpectedStatus) { return }
            }
        } catch { }
        if ([DateTime]::UtcNow -gt $nextNotice) {
            Write-Host "Waiting for $Name : $Url"
            $nextNotice = [DateTime]::UtcNow.AddSeconds(20)
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name health check timed out: $Url. Check logs before retrying."
}


# Keep native memory bounded as well as the Java heap on 16 GB local machines.
function Get-LocalJavaArguments([int]$Heap, [string]$Jar) {
    @('-Xms32m',"-Xmx${Heap}m",'-XX:+UseSerialGC','-XX:ActiveProcessorCount=2',
      '-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=48m','-XX:MaxDirectMemorySize=64m','-Xss512k',
      '-Dfile.encoding=UTF-8','-jar',$Jar)
}

function Get-LocalMysqlPort($Config) {
    $port=3306
    if($Config['MYSQL_PORT'] -and -not [int]::TryParse([string]$Config['MYSQL_PORT'],[ref]$port)){throw 'MYSQL_PORT must be a valid local TCP port.'}
    if($port -lt 1 -or $port -gt 65535){throw 'MYSQL_PORT must be between 1 and 65535.'}
    return $port
}

function Get-LocalJdbcUrl([string]$Service,[int]$Port) {
    if(@(Get-LocalServicePlan | Where-Object Name -eq $Service).Count -ne 1){throw 'Unknown local service database.'}
    return "jdbc:mysql://127.0.0.1:${Port}/simlect_${Service}?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&allowMultiQueries=true&useSSL=false"
}

function Get-LocalMemoryBudget {
    $os=Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
    [pscustomobject]@{PhysicalFreeMB=[math]::Floor($os.FreePhysicalMemory/1024);CommitFreeMB=[math]::Floor($os.FreeVirtualMemory/1024)}
}
function Assert-LocalMemoryBudget([string]$Stage,[int]$PhysicalMB=1024,[int]$CommitMB=2048) {
    $PhysicalMB=[math]::Max(1024,$PhysicalMB)
    $m=Get-LocalMemoryBudget
    Write-Host "$Stage memory: physical free $($m.PhysicalFreeMB) MB; commit free $($m.CommitFreeMB) MB"
    if($m.PhysicalFreeMB -lt $PhysicalMB -or $m.CommitFreeMB -lt $CommitMB){throw "$Stage stopped before allocating more memory. Need physical >= $PhysicalMB MB and commit >= $CommitMB MB. Close unused programs or use deploy/test-local-memory-safe.ps1; no service was killed by this check."}
    if($m.PhysicalFreeMB -lt 4096){Write-Warning "${Stage}: below the 4096 MB physical-memory target; hard safety checks passed."}
    return $m
}


# PowerShell 5.1 treats redirected native stderr as ErrorRecords. Progress output
# is not failure; preserve the native exit code and leave interpretation to callers.
function Invoke-LocalLoggedCommand([string]$Exe,[string[]]$Arguments,[string]$LogPath) {
    $ErrorActionPreference='Continue'
    & $Exe @Arguments *> $LogPath
    return $LASTEXITCODE
}

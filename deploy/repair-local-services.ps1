[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('user','product','stock','cart','coupon','order','pay','search','admin','gateway')]
    [string[]]$Services,
    [switch]$CheckOnly
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$backend=Join-Path $root 'Simlect-backend'
$runRoot=Join-Path $root 'logs/local-stack'
$statePath=Join-Path $runRoot 'processes.json'
$previous=@{}
$lock=$null
function Set-RepairEnv([string]$Key,[string]$Value) {
    if(-not $previous.ContainsKey($Key)){$previous[$Key]=[Environment]::GetEnvironmentVariable($Key,'Process')}
    [Environment]::SetEnvironmentVariable($Key,$Value,'Process')
}
function Test-RepairHealth([int]$Port) {
    try{$r=Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 5;return $r.status -eq 'UP'}catch{return $false}
}
function Get-RepairInstances([string]$Name,[int]$Port) {
    $r=Invoke-RestMethod -Uri "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=simlect-$Name" -TimeoutSec 5
    return @($r.hosts|Where-Object{$_.port -eq $Port -and $_.healthy -and $_.enabled})
}
try{
    $config=Read-LocalEnv (Join-Path $PSScriptRoot '.env.local')
    $mysqlPort=Get-LocalMysqlPort $config
    if($config['SIMLECT_PRODUCTION_READY'] -eq 'true' -or $env:SIMLECT_PRODUCTION_READY -eq 'true'){throw 'Local repair is not allowed in production mode.'}
    if(-not $CheckOnly){New-Item -ItemType Directory -Force -Path $runRoot|Out-Null;$lock=[IO.File]::Open((Join-Path $runRoot 'launcher.lock'),'OpenOrCreate','ReadWrite','None')}
    $entries=@(Read-LocalState $statePath)
    if(-not $CheckOnly){
        foreach($key in $config.Keys){if($key -match '^(SIMLECT_INTERNAL_TOKEN$|ADMIN_|MYSQL_|RABBIT_|PROJECT_FOLDER$|SIMLECT_MAIL_LOCAL_MOCK_ENABLED$|SIMLECT_PAY_LOCAL_MOCK_ENABLED$|SIMLECT_PAY_DEMO_CONFIRM_ENABLED$|SIMLECT_AFTER_SALE_REFUND_ENABLED$|AFTER_SALE_RETURN_(ADDRESS|WINDOW_DAYS|WINDOW_SOURCE)$)'){Set-RepairEnv $key $config[$key]}}
        $local=@{MYSQL_HOST='127.0.0.1';REDIS_HOST='127.0.0.1';REDIS_PORT='6379';RABBIT_HOST='127.0.0.1';ES_URIS='http://127.0.0.1:9200';SIMLECT_PRODUCTION_READY='false';SPRING_PROFILES_ACTIVE='low-memory';SEATA_ENABLED='false';SPRING_CLOUD_NACOS_DISCOVERY_IP='127.0.0.1';NACOS_ADDR='127.0.0.1:8848';MANAGEMENT_HEALTH_SENTINEL_ENABLED='false';MANAGEMENT_HEALTH_LIVENESSSTATE_ENABLED='false';MANAGEMENT_HEALTH_READINESSSTATE_ENABLED='false';JAVA_TOOL_OPTIONS='';_JAVA_OPTIONS='';JDK_JAVA_OPTIONS=''}
        foreach($key in $local.Keys){Set-RepairEnv $key $local[$key]}
    }
    $plan=@(Get-LocalServicePlan)
    foreach($name in @($Services|Select-Object -Unique)){
        $service=$plan|Where-Object Name -eq $name
        $record=@($entries|Where-Object Name -eq $name)
        if($record.Count -gt 1){throw "Duplicate registry entries for $name; no process stopped."}
        $owned=if($record.Count){Get-OwnedProcess $record[0]}else{$null}
        $health=Test-RepairHealth $service.Port
        $registered=@(Get-RepairInstances $name $service.Port)
        if($CheckOnly){[pscustomobject]@{Name=$name;OwnedAlive=($null -ne $owned);HealthUp=$health;Registered=$registered.Count};continue}
        if($null -eq $owned -and (Test-LocalPort $service.Port)){throw "$name port is held by an unverified process; nothing stopped."}
        if($null -ne $owned -and $health -and $registered.Count){Write-Host "Healthy: $name (kept running)";continue}
        [xml]$pom=Get-Content -LiteralPath (Join-Path $backend "$($service.Module)/pom.xml") -Raw
        $version=$pom.project.version;if(-not $version){$version=$pom.project.parent.version}
        $jar=Join-Path $backend "$($service.Module)/target/$($pom.project.artifactId)-$version.jar"
        if(-not(Test-Path -LiteralPath $jar)){throw "Executable missing for $name. Run the normal launcher with -Rebuild after stopping the stack."}
        $exe=if($record.Count){$record[0].Executable}elseif($config['LOCAL_JAVA_EXE']){$config['LOCAL_JAVA_EXE']}else{(Get-Command java.exe -CommandType Application|Select-Object -First 1).Source}
        if(-not(Test-Path -LiteralPath $exe)){throw "Java executable missing for $name"}
        if($null -ne $owned){Stop-OwnedProcesses $record}
        $folder=Join-Path $runRoot ('repair-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
        New-Item -ItemType Directory -Path $folder -Force|Out-Null
        $stdout=Join-Path $folder "$name.out.log";$stderr=Join-Path $folder "$name.err.log"
        Assert-LocalMemoryBudget $name 1024 2048 | Out-Null
        if($mysqlPort -ne 3306){if($name -eq 'gateway'){Set-RepairEnv 'SPRING_DATASOURCE_URL' ''}else{Set-RepairEnv 'SPRING_DATASOURCE_URL' (Get-LocalJdbcUrl $name $mysqlPort)}}
        $arguments=@(Get-LocalJavaArguments $service.Heap $jar)
        $process=Start-Process -FilePath $exe -ArgumentList (($arguments|ForEach-Object{ConvertTo-NativeArgument $_}) -join ' ') -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $new=[pscustomobject]@{Name=$name;ProcessId=$process.Id;StartTicks=$process.StartTime.ToUniversalTime().Ticks.ToString();Executable=$exe;Stdout=$stdout;Stderr=$stderr}
        $entries=@($entries|Where-Object Name -ne $name)+@($new);Save-LocalState $statePath $entries
        Wait-LocalHttp $name "http://127.0.0.1:$($service.Port)/actuator/health" 'UP' $process 120
        try { Assert-LocalMemoryBudget "$name ready" 1024 2048 | Out-Null } catch { Stop-OwnedProcesses @($new); $entries=@($entries|Where-Object Name -ne $name); Save-LocalState $statePath $entries; throw }
        while(@(Get-RepairInstances $name $service.Port).Count -eq 0){if([DateTime]::UtcNow -ge $deadline){throw "$name healthy but not registered in Nacos; check its log."};Start-Sleep -Seconds 2}
        Write-Host "Restored: $name; health UP; Nacos registered. Logs: $folder"
    }
}finally{
    if($null -ne $lock){$lock.Dispose()}
    foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
}


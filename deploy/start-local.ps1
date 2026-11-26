[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [switch]$Rebuild,
    [switch]$SkipAI,
    [switch]$WebOnly,
    [ValidateSet('full','guide','after-sale')][string]$Scenario='full'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
$ProjectRoot = Split-Path $PSScriptRoot -Parent
$BackendRoot = Join-Path $ProjectRoot 'Simlect-backend'
$AgentRoot = Join-Path $BackendRoot 'Simlect-agent'
$ConfigPath = Join-Path $PSScriptRoot '.env.local'
$RunRoot = Join-Path $ProjectRoot 'logs/local-stack'
$StatePath = Join-Path $RunRoot 'processes.json'
$ComposePath = Join-Path $PSScriptRoot 'docker-compose.middleware.yml'
$Started = @()
$Lock = $null
$PreviousEnvironment = @{}

function Set-LaunchEnv([string]$Name, [string]$Value) {
    if (-not $PreviousEnvironment.ContainsKey($Name)) {
        $PreviousEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function Resolve-LocalTool([string]$Name, [string]$ConfigKey) {
    $candidate = $Config[$ConfigKey]
    if (-not $candidate) { $candidate = $Name }
    $command = Get-Command $candidate -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) { throw "Missing $Name. Set $ConfigKey in $ConfigPath or install it and reopen the terminal." }
    return $command.Source
}
function Invoke-Checked([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path $Exe -Leaf) failed (exit $LASTEXITCODE). See the output above." }
}
function Start-LocalProcess([string]$Name, [string]$Exe, [string[]]$Arguments, [string]$Directory, [string]$Url, [string]$Status) {
    $stdout = Join-Path $SessionLogs "$Name.out.log"
    $stderr = Join-Path $SessionLogs "$Name.err.log"
    $argLine = ($Arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    Assert-LocalMemoryBudget $Name 1024 2048 | Out-Null
    Write-Host "Starting $Name ..." -ForegroundColor Cyan
    $process = Start-Process -FilePath $Exe -ArgumentList $argLine -WorkingDirectory $Directory -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $entry = [pscustomobject]@{
        Name=$Name; ProcessId=$process.Id; StartTicks=$process.StartTime.ToUniversalTime().Ticks.ToString()
        Executable=$Exe; Stdout=$stdout; Stderr=$stderr
    }
    $script:Started += $entry
    Save-LocalState $StatePath $script:Started
    Wait-LocalHttp $Name $Url $Status $process
    Assert-LocalMemoryBudget "$Name ready" 1024 2048 | Out-Null
    Write-Host "Ready: $Name" -ForegroundColor Green
}

try {
    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        if (-not $CheckOnly) { Copy-Item (Join-Path $PSScriptRoot '.env.local.example') $ConfigPath }
        throw "Fill LLM_API_KEY and EMBEDDING_API_KEY in $ConfigPath, then rerun. Template: deploy/.env.local.example"
    }
    $Config = Read-LocalEnv $ConfigPath
    $MysqlPort = Get-LocalMysqlPort $Config
    if ($Config['SIMLECT_PRODUCTION_READY'] -eq 'true' -or $env:SIMLECT_PRODUCTION_READY -eq 'true') {
        throw 'This launcher is for a local demo only; SIMLECT_PRODUCTION_READY=true is not supported.'
    }
    $Java = Resolve-LocalTool 'java.exe' 'LOCAL_JAVA_EXE'
    $Maven = Resolve-LocalTool 'mvn.cmd' 'LOCAL_MAVEN_CMD'
    $Node = Resolve-LocalTool 'node.exe' 'LOCAL_NODE_EXE'
    $Npm = Resolve-LocalTool 'npm.cmd' 'LOCAL_NPM_CMD'
    $Docker = (Get-Command docker.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    # Match Maven's JDK to the Java executable selected above.
    $JdkRoot = Split-Path (Split-Path $Java -Parent) -Parent
    if (-not (Test-Path (Join-Path $JdkRoot 'bin/javac.exe'))) { throw 'A full JDK 17+ is required, not a JRE.' }
    $JavaVersion = (& $Java --version | Out-String)
    if ($LASTEXITCODE -ne 0 -or $JavaVersion -notmatch '(?:openjdk|java) (\d+)' -or [int]$Matches[1] -lt 17) { throw 'JDK 17+ is required.' }
    $NodeVersion = [version]((& $Node -p 'process.versions.node') | Select-Object -Last 1)
    if (-not (($NodeVersion.Major -eq 20 -and $NodeVersion -ge [version]'20.19.0') -or $NodeVersion -ge [version]'22.12.0')) {
        throw 'Node.js 20.19+ (20.x) or 22.12+ is required by Vite.'
    }
    $Python = $null
    if (-not $SkipAI) {
        $requiredKeys = @('LLM_API_KEY')
        if ($Config['VECTOR_SEARCH_ENABLED'] -ne 'false') { $requiredKeys += 'EMBEDDING_API_KEY' }
        foreach ($key in $requiredKeys) {
            if (-not $Config[$key] -or $Config[$key] -match '^(your-|sk-local-placeholder|替换|填写)') {
                throw "Please fill $key in $ConfigPath. Keys are not printed. Use -SkipAI for the mall alone."
            }
        }
        $Python = Resolve-LocalTool 'python.exe' 'LOCAL_PYTHON_EXE'
        Invoke-Checked $Python @('-c', 'import sys; assert sys.version_info >= (3,11), "Python 3.11+ required"')
        if ($Config['EMBEDDING_DIMENSIONS'] -ne '1024') { throw 'EMBEDDING_DIMENSIONS must be 1024 to match the existing index.' }
    }
    $Services = @(Get-LocalServicePlan -Scenario $Scenario)
    if($Scenario -eq 'guide'){$WebOnly=$true}
    Write-Host "Scenario: $Scenario; Java services: $($Services.Name -join ', ')"
    $Ports = @($Services | ForEach-Object { $_.Port }) + @(6001)
    if (-not $WebOnly) { $Ports += 6002 }
    if (-not $SkipAI) { $Ports += @(7050,7060) }
    $BusyPorts = @($Ports | Where-Object { Test-LocalPort $_ })
    foreach ($service in $Services) {
        [xml]$pom = Get-Content (Join-Path $BackendRoot "$($service.Module)/pom.xml") -Raw
        $version = $pom.project.version
        if (-not $version) { $version = $pom.project.parent.version }
        $service.Jar = Join-Path $BackendRoot "$($service.Module)/target/$($pom.project.artifactId)-$version.jar"
    }
    if ($CheckOnly) {
        $Services | ForEach-Object { [pscustomobject]@{Service=$_.Name; Port=$_.Port; HeapMB=$_.Heap; JarExists=(Test-Path $_.Jar)} } | Format-Table
        Write-Host "AI enabled: $(-not $SkipAI); admin frontend: $(-not $WebOnly)"
        if ($BusyPorts.Count) { Write-Warning "Ports in use: $($BusyPorts -join ', '). Stop IDEA services / old frontends before using this launcher." }
        Write-Host 'Preflight finished. No Docker changes, builds, installations, or services started.'
        return
    }
    New-Item -ItemType Directory -Force -Path $RunRoot | Out-Null
    $Lock = [IO.File]::Open((Join-Path $RunRoot 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    $live = @(Read-LocalState $StatePath | Where-Object { $null -ne (Get-OwnedProcess $_) })
    if ($live.Count) { throw 'The launcher already has running services. Run stop-local.ps1 first.' }
    if ($BusyPorts.Count) { throw "Ports already in use: $($BusyPorts -join ', '). Stop the corresponding IDEA services / frontend terminals first. Nothing was killed." }
    $SessionLogs = Join-Path $RunRoot (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
    New-Item -ItemType Directory -Path $SessionLogs | Out-Null
    Write-Host "Logs: $SessionLogs"

    Assert-LocalMemoryBudget 'Stack preflight' 1536 7168 | Out-Null
    # One stable token shared by every Java process, Agent and MCP. Persist only locally.
    if (-not $Config['SIMLECT_INTERNAL_TOKEN']) {
        $Config['SIMLECT_INTERNAL_TOKEN'] = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
        $text = [IO.File]::ReadAllText($ConfigPath)
        $text = [regex]::Replace($text, '(?m)^SIMLECT_INTERNAL_TOKEN=.*$', ('SIMLECT_INTERNAL_TOKEN=' + $Config['SIMLECT_INTERNAL_TOKEN']))
        if ($text -notmatch '(?m)^SIMLECT_INTERNAL_TOKEN=') { $text += "`r`nSIMLECT_INTERNAL_TOKEN=$($Config['SIMLECT_INTERNAL_TOKEN'])`r`n" }
        [IO.File]::WriteAllText($ConfigPath, $text, (New-Object Text.UTF8Encoding($false)))
    }
    if ($Config['SIMLECT_INTERNAL_TOKEN'].Length -lt 32) { throw 'Use a SIMLECT_INTERNAL_TOKEN of at least 32 characters, or leave it blank for generation.' }
    if (-not $Config['ADMIN_PASSWORD']) { throw 'ADMIN_PASSWORD cannot be empty.' }
    foreach ($key in $Config.Keys) {
        # Deliberately export only application settings, never arbitrary PATH/HOME/etc.
        if ($key -match '^(VECTOR_SEARCH_ENABLED$|LLM_|EMBEDDING_|SIMLECT_INTERNAL_TOKEN$|ADMIN_|MYSQL_|RABBIT_|SIMLECT_MAIL_LOCAL_MOCK_ENABLED$|SIMLECT_PAY_LOCAL_MOCK_ENABLED$|SIMLECT_PAY_DEMO_CONFIRM_ENABLED$|SIMLECT_AFTER_SALE_REFUND_ENABLED$|AFTER_SALE_RETURN_(ADDRESS|WINDOW_DAYS|WINDOW_SOURCE)$|PROJECT_FOLDER$)') {
            Set-LaunchEnv $key $Config[$key]
        }
    }
    Set-LaunchEnv 'JAVA_HOME' $JdkRoot
    Set-LaunchEnv 'MAVEN_OPTS' '-Xms64m -Xmx512m -XX:ActiveProcessorCount=2'
    Set-LaunchEnv 'JAVA_TOOL_OPTIONS' ''
    Set-LaunchEnv '_JAVA_OPTIONS' ''
    Set-LaunchEnv 'JDK_JAVA_OPTIONS' ''
    Set-LaunchEnv 'SEATA_ENABLED' 'false'
    # Sentinel dashboard is intentionally omitted in this local demo.
    Set-LaunchEnv 'MANAGEMENT_HEALTH_SENTINEL_ENABLED' 'false'
    # Local launcher checks dependency health and the real gateway route separately.
    Set-LaunchEnv 'MANAGEMENT_HEALTH_LIVENESSSTATE_ENABLED' 'false'
    Set-LaunchEnv 'MANAGEMENT_HEALTH_READINESSSTATE_ENABLED' 'false'
    Set-LaunchEnv 'MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS' 'never'
    Set-LaunchEnv 'SPRING_PROFILES_ACTIVE' 'low-memory'
    Set-LaunchEnv 'SIMLECT_PRODUCTION_READY' 'false'
    Set-LaunchEnv 'SPRING_CLOUD_NACOS_DISCOVERY_IP' '127.0.0.1'
    Set-LaunchEnv 'NACOS_ADDR' '127.0.0.1:8848'
    Set-LaunchEnv 'MYSQL_HOST' '127.0.0.1'
    Set-LaunchEnv 'REDIS_HOST' '127.0.0.1'
    Set-LaunchEnv 'REDIS_PORT' '6379'
    Set-LaunchEnv 'RABBIT_HOST' '127.0.0.1'
    Set-LaunchEnv 'ES_URIS' 'http://127.0.0.1:9200'
    Set-LaunchEnv 'AGENT_HOST' '127.0.0.1'
    Set-LaunchEnv 'AGENT_PORT' '7050'
    Set-LaunchEnv 'NODE_OPTIONS' '--max-old-space-size=512'
    Set-LaunchEnv 'RAYON_NUM_THREADS' '2'
    Set-LaunchEnv 'PYTHONUTF8' '1'
    Set-LaunchEnv 'PYTHONUNBUFFERED' '1'
    Set-LaunchEnv 'OMP_NUM_THREADS' '2'
    Set-LaunchEnv 'OPENBLAS_NUM_THREADS' '2'
    if (-not $env:PROJECT_FOLDER) { Set-LaunchEnv 'PROJECT_FOLDER' ((Join-Path $ProjectRoot 'local-data/upload').Replace('\','/') + '/') }
    New-Item -ItemType Directory -Force -Path $env:PROJECT_FOLDER | Out-Null

    # Check Docker first; no automatic Docker Desktop restart or native service changes.
    Invoke-Checked $Docker @('info','--format','{{.ServerVersion}}')
    # Build/install before starting the stack to reduce peak memory usage.
    $BuildStamp = Join-Path $RunRoot 'java-build.ok'
    if ($Rebuild -or -not (Test-Path $BuildStamp) -or @($Services | Where-Object { -not (Test-Path $_.Jar) }).Count) {
        Push-Location $BackendRoot
        try { Invoke-Checked $Maven @('package','-Dmaven.test.skip=true','-Djacoco.skip=true','--no-transfer-progress') } finally { Pop-Location }
        foreach ($service in $Services) { if (-not (Test-Path $service.Jar)) { throw "Missing executable jar: $($service.Jar)" } }
        Set-Content -LiteralPath $BuildStamp -Value (Get-Date -Format o)
    }
    $Frontends = @('web')
    if (-not $WebOnly) { $Frontends += 'admin' }
    foreach ($front in $Frontends) {
        $directory = Join-Path $ProjectRoot "Simlect-front/Simlect-$front"
        if (-not (Test-Path (Join-Path $directory 'node_modules/vite/bin/vite.js'))) {
            Push-Location $directory
            try { Invoke-Checked $Npm @('ci','--no-audit','--no-fund') } finally { Pop-Location }
        }
    }
    if (-not $SkipAI) {
        $VenvPython = Join-Path $AgentRoot '.venv/Scripts/python.exe'
        if (-not (Test-Path $VenvPython)) { Invoke-Checked $Python @('-m','venv',(Join-Path $AgentRoot '.venv')) }
        $requirements = Join-Path $AgentRoot 'requirements-runtime.txt'
        $hash = (Get-FileHash $requirements -Algorithm SHA256).Hash
        $stamp = Join-Path $AgentRoot '.venv/simlect-requirements.sha256'
        if (-not (Test-Path $stamp) -or (Get-Content $stamp -Raw).Trim() -ne $hash) {
            Invoke-Checked $VenvPython @('-m','pip','install','-r',$requirements)
            Set-Content -LiteralPath $stamp -Value $hash
        }
        Invoke-Checked $VenvPython @('-m','pip','check')
    }

    $composeArgs=@('compose','-f',$ComposePath)
    if($MysqlPort -ne 3306){$composeArgs+=@('-f',(Join-Path $PSScriptRoot 'docker-compose.local-port.yml'));Set-LaunchEnv 'MYSQL_PORT' "$MysqlPort"}
    Invoke-Checked $Docker ($composeArgs+@('up','-d','--wait','--wait-timeout','300','mysql','redis','rabbitmq','nacos','elasticsearch'))
    # This launcher resumes the already-initialized demo; never reimport/drop business data.
    Set-LaunchEnv 'MYSQL_PWD' $Config['MYSQL_PASSWORD']
    $schemaQuery = "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('simlect_user','simlect_product','simlect_stock','simlect_cart','simlect_coupon','simlect_order','simlect_pay','simlect_search','simlect_admin','simlect_agent');"
    $schemaCount = & $Docker exec -e MYSQL_PWD simlect-mysql mysql "-u$($Config['MYSQL_USER'])" -N -B -e $schemaQuery
    if ($LASTEXITCODE -ne 0 -or "$schemaCount".Trim() -ne '10') { throw 'Business databases are missing or MySQL login failed. Follow README database initialization; this launcher does not overwrite data.' }
    $agentTable = & $Docker exec -e MYSQL_PWD simlect-mysql mysql "-u$($Config['MYSQL_USER'])" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='simlect_agent' AND table_name='agent_message';"
    if ($LASTEXITCODE -ne 0 -or "$agentTable".Trim() -ne '1') { throw 'Missing simlect_agent.agent_message. Import sql/09_agent.sql before enabling AI.' }
    Set-LaunchEnv 'MYSQL_PWD' ''
    # Spring AI appends /v1 itself; Python's OpenAI client expects the complete /v1 base.
    $PythonEmbeddingBase = $Config['EMBEDDING_BASE_URL']
    Set-LaunchEnv 'EMBEDDING_BASE_URL' ($PythonEmbeddingBase.TrimEnd('/') -replace '/v1$', '')
    # Python clients use /v1; Spring AI adds /v1 to its configured root URL.
    $PythonLlmBase = $Config['LLM_BASE_URL']
    Set-LaunchEnv 'LLM_BASE_URL' ($PythonLlmBase.TrimEnd('/') -replace '/v1$', '')
    foreach ($service in $Services) {
        if($MysqlPort -ne 3306){if($service.Name -eq 'gateway'){Set-LaunchEnv 'SPRING_DATASOURCE_URL' ''}else{Set-LaunchEnv 'SPRING_DATASOURCE_URL' (Get-LocalJdbcUrl $service.Name $MysqlPort)}}
        $arguments = @(Get-LocalJavaArguments $service.Heap $service.Jar)
        Start-LocalProcess $service.Name $Java $arguments $BackendRoot "http://127.0.0.1:$($service.Port)/actuator/health" 'UP'
    }
    # Nacos discovery may lag the individual service health checks.
    Wait-LocalHttp 'gateway-user-route' 'http://127.0.0.1:8080/api/account/autoLogin' 'success'
    if (-not $SkipAI) {
        Set-LaunchEnv 'EMBEDDING_BASE_URL' $PythonEmbeddingBase
        Set-LaunchEnv 'LLM_BASE_URL' $PythonLlmBase
        Set-LaunchEnv 'JAVA_WEB_URL' 'http://127.0.0.1:8080'
        Set-LaunchEnv 'MCP_SERVER_URL' 'http://127.0.0.1:7060'
        Set-LaunchEnv 'INTERNAL_READ_BACKEND' 'http'
        Set-LaunchEnv 'MYSQL_HOST' '127.0.0.1'
        Set-LaunchEnv 'MYSQL_PORT' "$MysqlPort"
        Set-LaunchEnv 'MYSQL_DATABASE' 'simlect_agent'
        Set-LaunchEnv 'REDIS_HOST' '127.0.0.1'
        Set-LaunchEnv 'REDIS_PORT' '6379'
        Set-LaunchEnv 'REDIS_DB' '0'
        Set-LaunchEnv 'ES_HOSTS' 'http://127.0.0.1:9200'
        Set-LaunchEnv 'ES_VECTOR_DIMENSIONS' '1024'
        Set-LaunchEnv 'FASTMCP_HOST' '127.0.0.1'
        Set-LaunchEnv 'FASTMCP_PORT' '7060'
        Start-LocalProcess 'mcp' $VenvPython @('-m','app.mcp_server') $AgentRoot 'http://127.0.0.1:7060/health' 'ok'
        Start-LocalProcess 'agent' $VenvPython @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port','7050','--workers','1') $AgentRoot 'http://127.0.0.1:7050/health' 'ok'
    }
    Set-LaunchEnv 'VITE_API_PROXY_TARGET' 'http://127.0.0.1:8080'
    Set-LaunchEnv 'VITE_AGENT_PROXY_TARGET' 'http://127.0.0.1:8080'
    Set-LaunchEnv 'VITE_WS_PROXY_TARGET' 'ws://127.0.0.1:8080'
    foreach ($front in $Frontends) {
        $port = 6001
        $suffix = '/'
        if ($front -eq 'admin') { $port = 6002; $suffix = '/admin/' }
        $directory = Join-Path $ProjectRoot "Simlect-front/Simlect-$front"
        Start-LocalProcess "frontend-$front" $Node @((Join-Path $directory 'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',"$port",'--strictPort') $directory "http://127.0.0.1:$port$suffix" ''
    }
    foreach ($entry in $Started) {
        if ($null -eq (Get-OwnedProcess $entry)) { throw "$($entry.Name) exited during startup. Check $($entry.Stderr)" }
    }
    Write-Host "`nREADY: http://127.0.0.1:6001/" -ForegroundColor Green
    if (-not $WebOnly) { Write-Host 'Admin: http://127.0.0.1:6002/admin/' }
    if (-not $SkipAI) { Write-Host 'Agent + MCP healthy. API keys/model access still need a real chat to verify.' }
    Write-Host "Email demo codes: $SessionLogs/user.out.log"
    Write-Host 'Stop: double-click stop-local.cmd. Changes to Java source: rerun with -Rebuild.'
} catch {
    Write-Host "`nSTART FAILED: $($_.Exception.Message)" -ForegroundColor Red
    if ($Started.Count) {
        Write-Host 'Stopping only processes started by this attempt. Docker data and existing services are kept.'
        Stop-OwnedProcesses $Started
        Save-LocalState $StatePath @()
    }
    exit 1
} finally {
    if ($null -ne $Lock) { $Lock.Dispose() }
    foreach ($key in $PreviousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $PreviousEnvironment[$key], 'Process') }
}



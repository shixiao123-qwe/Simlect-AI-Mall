[CmdletBinding()]
param([ValidateSet('Quick','All','AfterSale','Reliability','Product')][string]$Suite='Quick',[switch]$ResumeStack)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$state=Join-Path $root 'logs/local-stack/processes.json'
$folder=Join-Path $root ('logs/memory-safe-tests/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $folder -Force|Out-Null
$lock=$null;$paused=$false;$failures=@();$resumeCode=0;$envBefore=@{}
function Set-TestEnv($key,$value){if(-not $envBefore.ContainsKey($key)){$envBefore[$key]=[Environment]::GetEnvironmentVariable($key,'Process')};[Environment]::SetEnvironmentVariable($key,$value,'Process')}
function Run-Test($name,$exe,[string[]]$arguments,$directory){
    $budget=Assert-LocalMemoryBudget $name 1536 3072
    [pscustomobject]@{Stage=$name;Time=(Get-Date -Format o);PhysicalFreeMB=$budget.PhysicalFreeMB;CommitFreeMB=$budget.CommitFreeMB}|ConvertTo-Json -Compress|Add-Content (Join-Path $folder 'memory.jsonl')
    Push-Location $directory
    try{$code=Invoke-LocalLoggedCommand $exe $arguments (Join-Path $folder "$name.log");if($code){$script:failures+=$name};Write-Host "$name exit=$code"}finally{Pop-Location}
}
try{
    $localConfig=Read-LocalEnv (Join-Path $PSScriptRoot '.env.local')
    if($localConfig['SIMLECT_PRODUCTION_READY'] -eq 'true' -or $env:SIMLECT_PRODUCTION_READY -eq 'true'){throw 'Local tests cannot pause production services.'}
    $lock=[IO.File]::Open((Join-Path $root 'logs/local-stack/launcher.lock'),'OpenOrCreate','ReadWrite','None')
    $records=@(Read-LocalState $state);Save-LocalState (Join-Path $folder 'resume-processes.json') $records
    $owned=@($records|Where-Object{$null -ne (Get-OwnedProcess $_)})
    # Save intent before stopping, so finally restores even if one stop fails.
    $paused=$ResumeStack -or $owned.Count -gt 0
    Stop-OwnedProcesses $owned
    $remaining=@($records|Where-Object{$null -ne (Get-OwnedProcess $_)})
    Save-LocalState $state $remaining
    Set-TestEnv PYTHONPATH (Join-Path $root 'Simlect-backend/Simlect-agent')
    Set-TestEnv NODE_OPTIONS '--max-old-space-size=512'
    Set-TestEnv SIMLECT_AFTER_SALE_DB_TEST 'false'
    Set-TestEnv MAVEN_OPTS '-Xms32m -Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:ActiveProcessorCount=2'
    Run-Test python (Join-Path $root 'Simlect-backend/Simlect-agent/.venv/Scripts/python.exe') @('-m','pytest','evals/test_shopping.py','evals/test_after_sale.py','evals/test_public_catalog.py','evals/test_shopping_presentation.py','evals/test_business_workflows.py','Simlect-backend/Simlect-agent/tests','-q','-p','no:cacheprovider',"--junitxml=$folder/python.xml","--basetemp=$folder/pytest-temp") $root
    Run-Test business-flow-report (Join-Path $root 'Simlect-backend/Simlect-agent/.venv/Scripts/python.exe') @('evals/run_business_flows.py','--junit-input',(Join-Path $folder 'python.xml')) $root
    foreach($name in @('check-home-feed','check-local-payment','check-after-sale-fulfillment','check-shopping-studio')){Run-Test $name 'node.exe' @("scripts/$name.mjs") (Join-Path $root 'Simlect-front/Simlect-web')}
    Run-Test captcha 'node.exe' @('scripts/check-login-captcha.mjs') (Join-Path $root 'Simlect-front/Simlect-admin')
    Run-Test typecheck 'node.exe' @('node_modules/vue-tsc/bin/vue-tsc.js','-b') (Join-Path $root 'Simlect-front/Simlect-web')
    if($Suite -in @('All','AfterSale','Reliability','Product')){
        $config=Read-LocalEnv (Join-Path $PSScriptRoot '.env.local')
        $repo=Join-Path $env:USERPROFILE '.m2/repository'
        $maven=(Get-Command mvn.cmd -CommandType Application|Select-Object -First 1).Source
        $settings=@((Join-Path (Split-Path (Split-Path $maven -Parent) -Parent) 'conf/settings.xml'),(Join-Path $env:USERPROFILE '.m2/settings.xml'))
        foreach($settingsFile in $settings){if(Test-Path -LiteralPath $settingsFile){[xml]$xml=[IO.File]::ReadAllText($settingsFile,[Text.Encoding]::UTF8);if($xml.settings.localRepository){$repo=[Environment]::ExpandEnvironmentVariables([string]$xml.settings.localRepository);$repo=$repo.Replace('${user.home}',$env:USERPROFILE)}}}
        $agent=Join-Path $repo 'net/bytebuddy/byte-buddy-agent'
        $premain=Get-ChildItem -LiteralPath $agent -Filter 'byte-buddy-agent-*.jar' -Recurse -ErrorAction Stop|Where-Object Name -notmatch 'sources|javadoc'|Sort-Object FullName -Descending|Select-Object -First 1
        if(-not $premain){throw 'Cached ByteBuddy agent missing; no download performed.'}
        Set-TestEnv MAVEN_OPTS ($env:MAVEN_OPTS+' -javaagent:"'+$premain.FullName+'"')
        $tests='ProductControllerTest,SysCategoryServiceImplTest,ProductPaginationMySqlTest,AfterSalePolicyRulesTest,AfterSaleWorkflowServiceTest,AfterSaleWorkflowControllerTest,AfterSaleControllerTest,AfterSaleServiceTest,OrderInfoServiceImplTest,OrderStateMachineTest,RabbitMQPayOrderDeadListenerComponentTest,LocalDemoPaymentServiceTest,LocalDemoPaymentControllerTest,OrderControllerTest,OrderAgentInternalControllerTest,PayOrderTaskTest,PayInternalServiceTest,PayTradeRecordServiceImplTest,PayChannel4AliPayTest,PayNotifyControllerTest,PayInternalControllerTest,UserCouponServiceImplTest,DiscountCouponServiceImplTest,CouponInternalServiceTest,CouponRushStockServiceTest,DiscountCouponControllerTest,ProductCartServiceImplTest,ProductCartControllerTest,CartInternalServiceTest,AiProductFilterServiceTest,AiFilterControllerTest,StockReadBatchTest,SkuStockServiceTest,StockInternalControllerTest,SearchControllerTest,EsSearchComponentTest,InternalTokenGlobalFilterTest,AuthGlobalFilterTest,GatewayTokenResolverTest,GatewayProductionSafetyValidatorTest,OrderPayAmountUtilTest'
        $modules=if($Suite -eq 'AfterSale'){@('Simlect-order/app')}elseif($Suite -eq 'Product'){@('Simlect-product/app')}elseif($Suite -eq 'Reliability'){@('Simlect-product/app','Simlect-stock/app','Simlect-order/app','Simlect-gateway')}else{@('Simlect-product/app','Simlect-stock/app','Simlect-cart/app','Simlect-order/app','Simlect-pay/app','Simlect-coupon/app','Simlect-search','Simlect-gateway')}
        $goal=if($Suite -in @('AfterSale','Reliability','Product')){'package'}else{'test'}
        foreach($module in $modules){
            Run-Test ($module.Replace('/','-')) 'mvn.cmd' @('-o','-f','Simlect-backend/pom.xml','-pl',$module,'-am',$goal,"-Dtest=$tests",'-Dsurefire.failIfNoSpecifiedTests=false','-Djacoco.skip=true','-DforkCount=0','--no-transfer-progress') $root
        }
    }
}catch{$failures+= 'Runner setup failed; '+$_.Exception.GetType().Name;Write-Warning ('Runner setup failed at line '+$_.InvocationInfo.ScriptLineNumber)}finally{
    if($lock){$lock.Dispose()}
    foreach($key in $envBefore.Keys){[Environment]::SetEnvironmentVariable($key,$envBefore[$key],'Process')}
    if($paused){
        Write-Host 'Restoring the local stack. Database volumes were kept.'
        # External admin dev server is kept; never claim or kill its port.
        $restore=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'start-local.ps1'))
        # Restore the original scenario instead of inflating a guide test into the full stack.
        $javaNames=@($records.Name|Where-Object {$_ -in @(Get-LocalServicePlan|ForEach-Object {$_.Name})}|Sort-Object)
        foreach($scenario in @('guide','after-sale')){
            $expected=@(Get-LocalServicePlan -Scenario $scenario|ForEach-Object {$_.Name}|Sort-Object)
            if(($javaNames -join ',') -eq ($expected -join ',')){$restore+=@('-Scenario',$scenario);break}
        }
        if((Test-LocalPort 6002) -or ($owned.Name -notcontains 'frontend-admin')){$restore+='-WebOnly'}
        # Wait for the launcher PID, not an inherited output pipe held by its services.
        $restoreArgs=($restore|ForEach-Object{ConvertTo-NativeArgument $_}) -join ' '
        $restoreProcess=Start-Process -FilePath 'powershell.exe' -ArgumentList $restoreArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $folder 'restore.log') -RedirectStandardError (Join-Path $folder 'restore.err.log')
        $null=$restoreProcess.Handle # Cache the handle before exit (Windows PowerShell 5.1).
        if(-not $restoreProcess.WaitForExit(600000)){throw 'Restore launcher timed out; inspect logs and registry before retrying.'}
        $resumeCode=$restoreProcess.ExitCode
        if($null -eq $resumeCode){throw 'Restore exit code unavailable; verify process and health rather than treating null as success.'}
        Write-Host "Restore exit=$resumeCode (see $folder/restore.log)"
    }
    [pscustomobject]@{Suite=$Suite;Failures=$failures;RestoreExit=$resumeCode;Finished=(Get-Date -Format o)}|ConvertTo-Json -Depth 5|Set-Content (Join-Path $folder 'result.json') -Encoding utf8
}
Write-Host "Test evidence: $folder"
if($failures.Count -or $resumeCode){exit 1}













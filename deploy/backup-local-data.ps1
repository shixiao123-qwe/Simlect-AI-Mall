[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-stack-common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$config=Read-LocalEnv (Join-Path $PSScriptRoot '.env.local')
if($config['SIMLECT_PRODUCTION_READY'] -eq 'true' -or $env:SIMLECT_PRODUCTION_READY -eq 'true'){throw 'Local backup script refuses production mode.'}
if(-not $config['MYSQL_PASSWORD']){throw 'Local MYSQL_PASSWORD missing'}
$folder=Join-Path $root ('backups/mysql/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $folder|Out-Null
$file=Join-Path $folder 'simlect-databases.sql'
$user=$config['MYSQL_USER'];if(-not $user){$user='root'}
$arguments=@('exec','-e','MYSQL_PWD','simlect-mysql','mysqldump',"-u$user",'--default-character-set=utf8mb4','--single-transaction','--routines','--events','--hex-blob','--no-tablespaces','--set-gtid-purged=OFF','--databases','simlect_user','simlect_product','simlect_stock','simlect_cart','simlect_order','simlect_pay','simlect_coupon','simlect_search','simlect_admin','simlect_agent','nacos','seata')
$info=New-Object Diagnostics.ProcessStartInfo
$info.FileName=(Get-Command docker.exe -CommandType Application|Select-Object -First 1).Source
$info.Arguments=($arguments|ForEach-Object{ConvertTo-NativeArgument $_}) -join ' '
$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
$info.EnvironmentVariables['MYSQL_PWD']=$config['MYSQL_PASSWORD']
$process=New-Object Diagnostics.Process;$process.StartInfo=$info
$stream=$null
try{
 if(-not $process.Start()){throw 'Could not start mysqldump'}
 $errors=$process.StandardError.ReadToEndAsync()
 $stream=[IO.File]::Create($file+'.partial')
 $process.StandardOutput.BaseStream.CopyTo($stream)
 $stream.Dispose();$stream=$null
 $process.WaitForExit()
 $errorText=$errors.GetAwaiter().GetResult()
 if($process.ExitCode -ne 0){[IO.File]::WriteAllText((Join-Path $folder 'backup-error.log'),$errorText);throw 'mysqldump failed; partial file is not a valid backup'}
 Move-Item -LiteralPath ($file+'.partial') -Destination $file
 $hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
 [pscustomobject]@{Created=(Get-Date -Format o);File='simlect-databases.sql';Bytes=(Get-Item $file).Length;SHA256=$hash;Note='Contains private local account/order data. Keep local; do not commit or upload.'}|ConvertTo-Json|Set-Content (Join-Path $folder 'manifest.json') -Encoding utf8
 Write-Host "Verified backup written: $file"
}finally{if($stream){$stream.Dispose()};$process.Dispose()}

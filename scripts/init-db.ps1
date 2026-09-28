<#
  pay-recon 建库建表脚本（离带外执行）

  为什么不在应用启动时自动建表：
    pay_flow 是「只追加，永不修改、永不删除」的审计表。任何"启动即重放 DDL"
    的做法都可能把 DROP / TRUNCATE / ALTER 混进启动路径。因此
    application.yml 里写死 spring.sql.init.mode=never，建表只走这个脚本。

  用法（PATH 在本机是空的，所以全用绝对路径）：
    pwsh -File scripts\init-db.ps1

  连接参数可用环境变量覆盖（默认值只是本机 demo 的弱口令）：
    DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME
#>

$ErrorActionPreference = 'Stop'

$MysqlExe  = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$DbHost    = if ($env:DB_HOST)     { $env:DB_HOST }     else { '127.0.0.1' }
$DbPort    = if ($env:DB_PORT)     { $env:DB_PORT }     else { '3306' }
$DbUser    = if ($env:DB_USER)     { $env:DB_USER }     else { 'root' }
$DbPass    = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { '000000' }
$DbName    = if ($env:DB_NAME)     { $env:DB_NAME }     else { 'pay_recon' }

$schemaSql = Join-Path $PSScriptRoot '..\src\main\resources\schema.sql'
$schemaSql = (Resolve-Path $schemaSql).Path

Write-Host "==> schema: $schemaSql"

# 1) 建库
& $MysqlExe -h $DbHost -P $DbPort -u $DbUser "-p$DbPass" -e `
  "CREATE DATABASE IF NOT EXISTS $DbName DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }
if ($LASTEXITCODE -ne 0) { throw "CREATE DATABASE 失败，exit=$LASTEXITCODE" }

# 2) 建表（schema.sql 全部 IF NOT EXISTS，可重复执行）
Get-Content -Raw -Encoding UTF8 $schemaSql | & $MysqlExe -h $DbHost -P $DbPort -u $DbUser "-p$DbPass" $DbName 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }
if ($LASTEXITCODE -ne 0) { throw "建表失败，exit=$LASTEXITCODE" }

# 3) 初始化单账户（幂等）
& $MysqlExe -h $DbHost -P $DbPort -u $DbUser "-p$DbPass" $DbName -e `
  "INSERT INTO pay_account (account_no, balance, updated_at) VALUES ('ACC-001', 0.00, NOW(3)) ON DUPLICATE KEY UPDATE account_no = account_no;" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }
if ($LASTEXITCODE -ne 0) { throw "初始化账户失败，exit=$LASTEXITCODE" }

# 4) 回显证据
Write-Host "==> SHOW TABLES"
& $MysqlExe -h $DbHost -P $DbPort -u $DbUser "-p$DbPass" $DbName -e "SHOW TABLES;" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }

Write-Host "==> pay_order 唯一索引（幂等唯一来源，必须存在）"
& $MysqlExe -h $DbHost -P $DbPort -u $DbUser "-p$DbPass" $DbName -e `
  "SHOW INDEX FROM pay_order WHERE Key_name = 'uk_merchant_order_no';" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }

Write-Host "==> account_entry 唯一索引（幂等入账唯一来源，必须存在）"
& $MysqlExe -h $DbHost -P $DbPort -u $DbUser "-p$DbPass" $DbName -e `
  "SHOW INDEX FROM account_entry WHERE Key_name = 'uk_order_id';" 2>&1 |
  Where-Object { $_ -notmatch 'Using a password' }

Write-Host "==> 初始化完成"

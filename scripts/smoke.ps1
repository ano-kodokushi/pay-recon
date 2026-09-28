# pay-recon end-to-end smoke test (ASCII only: this shell reads .ps1 as GBK)
# Proves the whole chain works before the 6 concurrency tests are written:
#   create order -> channel pay -> callback notify -> credited
# Uses real MySQL + real HTTP, no layer bypassed.

$ErrorActionPreference = 'Stop'
$Base  = 'http://127.0.0.1:8080'
$Mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
# Connection settings come from env vars, defaulting to the local demo values.
$DbHost = if ($env:DB_HOST)     { $env:DB_HOST }     else { '127.0.0.1' }
$DbPort = if ($env:DB_PORT)     { $env:DB_PORT }     else { '3306' }
$DbUser = if ($env:DB_USER)     { $env:DB_USER }     else { 'root' }
$DbPass = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { '000000' }
$DbName = if ($env:DB_NAME)     { $env:DB_NAME }     else { 'pay_recon' }
$DbArgs = @('-h', $DbHost, '-P', $DbPort, '-u', $DbUser, "-p$DbPass", $DbName)

function Sql([string]$q) {
    # mysql writes the password warning to stderr; with ErrorActionPreference=Stop
    # that would abort the script, so relax it locally for this native call.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Mysql @DbArgs --connect-timeout=10 -N -B -e $q 2>&1 |
               Where-Object { $_ -notmatch 'Using a password' }
    } finally {
        $ErrorActionPreference = $prev
    }
    return ($out | Out-String).Trim()
}

$no = 'SMOKE-' + (Get-Date -Format 'yyyyMMddHHmmss')
$amount = '88.50'

Write-Host "=== 1) POST /api/order/create  no=$no amount=$amount"
$body = @{ merchantOrderNo = $no; amount = $amount } | ConvertTo-Json -Compress
$r1 = Invoke-RestMethod -Uri "$Base/api/order/create" -Method Post -Body $body -ContentType 'application/json; charset=utf-8'
Write-Host ("    call1: " + ($r1 | ConvertTo-Json -Compress))
$r2 = Invoke-RestMethod -Uri "$Base/api/order/create" -Method Post -Body $body -ContentType 'application/json; charset=utf-8'
Write-Host ("    call2: " + ($r2 | ConvertTo-Json -Compress))

Write-Host "=== 2) DB: pay_order rows must be 1"
Write-Host ("    rows=" + (Sql "SELECT COUNT(*) FROM pay_order WHERE merchant_order_no='$no';"))
Write-Host ("    status=" + (Sql "SELECT status FROM pay_order WHERE merchant_order_no='$no';"))
Write-Host ("    pay_flow CREATE=" + (Sql "SELECT COUNT(*) FROM pay_flow f JOIN pay_order o ON f.order_id=o.id WHERE o.merchant_order_no='$no' AND f.event_type='CREATE';"))

Write-Host "=== 3) POST /mock/channel/pay"
$payBody = @{ merchantOrderNo = $no; amount = $amount } | ConvertTo-Json -Compress
$pay = Invoke-RestMethod -Uri "$Base/mock/channel/pay" -Method Post -Body $payBody -ContentType 'application/json; charset=utf-8'
Write-Host ("    " + ($pay | ConvertTo-Json -Compress))
$tradeNo = $pay.tradeNo

Write-Host "=== 4) POST /mock/channel/notify times=1"
$notifyBody = @{ merchantOrderNo = $no; tradeNo = $tradeNo; amount = $amount; times = 1 } | ConvertTo-Json -Compress
$sent = Invoke-RestMethod -Uri "$Base/mock/channel/notify" -Method Post -Body $notifyBody -ContentType 'application/json; charset=utf-8'
Write-Host ("    sent: " + ($sent | ConvertTo-Json -Compress))

Write-Host "=== 5) DB: expect SUCCESS, credited exactly once, paid_at set"
Write-Host ("    status=" + (Sql "SELECT status FROM pay_order WHERE merchant_order_no='$no';"))
Write-Host ("    channel_trade_no=" + (Sql "SELECT channel_trade_no FROM pay_order WHERE merchant_order_no='$no';"))
Write-Host ("    paid_at=" + (Sql "SELECT paid_at FROM pay_order WHERE merchant_order_no='$no';"))
Write-Host ("    account_entry count=" + (Sql "SELECT COUNT(*) FROM account_entry e JOIN pay_order o ON e.order_id=o.id WHERE o.merchant_order_no='$no';"))
Write-Host ("    credited amount=" + (Sql "SELECT e.amount FROM account_entry e JOIN pay_order o ON e.order_id=o.id WHERE o.merchant_order_no='$no';"))
Write-Host ("    pay_flow rows:")
Write-Host (Sql "SELECT CONCAT(f.id,' | ',f.event_type,' | ',f.source,' | ',IFNULL(f.from_status,'-'),' -> ',IFNULL(f.to_status,'-')) FROM pay_flow f JOIN pay_order o ON f.order_id=o.id WHERE o.merchant_order_no='$no' ORDER BY f.id;")

Write-Host "=== 6) replay callback times=4 (expect advance 1, credit still 1, paid_at unchanged)"
$before = Sql "SELECT paid_at FROM pay_order WHERE merchant_order_no='$no';"
$notifyBody4 = @{ merchantOrderNo = $no; tradeNo = $tradeNo; amount = $amount; times = 4 } | ConvertTo-Json -Compress
$sent4 = Invoke-RestMethod -Uri "$Base/mock/channel/notify" -Method Post -Body $notifyBody4 -ContentType 'application/json; charset=utf-8'
Write-Host ("    sent: " + ($sent4 | ConvertTo-Json -Compress))
$after = Sql "SELECT paid_at FROM pay_order WHERE merchant_order_no='$no';"
Write-Host ("    paid_at before=$before after=$after unchanged=" + ($before -eq $after))
Write-Host ("    account_entry count (MUST remain 1)=" + (Sql "SELECT COUNT(*) FROM account_entry e JOIN pay_order o ON e.order_id=o.id WHERE o.merchant_order_no='$no';"))
Write-Host ("    pay_flow CALLBACK rows=" + (Sql "SELECT COUNT(*) FROM pay_flow f JOIN pay_order o ON f.order_id=o.id WHERE o.merchant_order_no='$no' AND f.event_type='CALLBACK';"))
Write-Host ("    pay_flow advance rows (to_status=SUCCESS)=" + (Sql "SELECT COUNT(*) FROM pay_flow f JOIN pay_order o ON f.order_id=o.id WHERE o.merchant_order_no='$no' AND f.to_status='SUCCESS';"))

Write-Host "=== 7) illegal transition: force channel FAILED on a SUCCESS order"
Invoke-RestMethod -Uri "$Base/mock/channel/control/force-status?merchantOrderNo=$no&status=FAILED" -Method Post | Out-Null
$failBody = @{ merchantOrderNo = $no; tradeNo = $tradeNo; amount = $amount; status = 'FAILED' } | ConvertTo-Json -Compress
$sentFail = Invoke-RestMethod -Uri "$Base/mock/channel/notify" -Method Post -Body $failBody -ContentType 'application/json; charset=utf-8'
Write-Host ("    sent: " + ($sentFail | ConvertTo-Json -Compress))
Write-Host ("    status MUST still be SUCCESS=" + (Sql "SELECT status FROM pay_order WHERE merchant_order_no='$no';"))
Write-Host ("    account_entry count (MUST remain 1)=" + (Sql "SELECT COUNT(*) FROM account_entry e JOIN pay_order o ON e.order_id=o.id WHERE o.merchant_order_no='$no';"))

Write-Host "=== SMOKE DONE ==="

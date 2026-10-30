#Requires -Version 5.1
<#
.SYNOPSIS
  演示数据自洽性检查（M5 五轮审查 M5R-02 / 03 §44）。

.DESCRIPTION
  W7 的 demo 探针原来只数「5 条预警、若干行」，不验证预警是否真的由分数触发。
  本脚本对运行中的 dev/demo 库做**只读**查询，按当前规则重算演示预警，并核对：
    1. 活动 TOTAL_LOW 必须总分 < 70；
    2. 活动 KEY_DIMENSION_LOW 必须至少有一个关键维度 < 70；
    3. 活动 TREND_DECLINE 必须为 0（演示库只有 Q2/Q3 两个季度，默认 N=2 需要三个季度）；
    4. 演示 Q2/Q3 的总分必须等于维度加权和；
    5. 定性指标的 score_value 必须等于 raw_value × 20；
    6. 演示预警总数 5、活动预警 4（1 已处理 + 3 未读，另有 1 条 SUPERSEDED）。

  凭据从 `spe-mysql` 容器的 `MYSQL_ROOT_PASSWORD` 读取，不落文件、不打印。
  只读查询；不会修改任何数据。容器名/数据库名可用参数覆盖。

.EXAMPLE
  .\scripts\check-demo-consistency.ps1 -Container spe-mysql -Database spe
#>
[CmdletBinding()]
param(
  [string]$Container = 'spe-mysql',
  [string]$Database = 'spe'
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Write-Host '[FAIL] 未找到 docker 命令' -ForegroundColor Red
  exit 1
}

$running = docker inspect $Container --format '{{.State.Running}}' 2>$null
if ($running -ne 'true') {
  Write-Host "[FAIL] 容器 $Container 未运行" -ForegroundColor Red
  exit 1
}

$envText = docker inspect $Container --format '{{range .Config.Env}}{{println .}}{{end}}' 2>$null
$password = ($envText | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' }) -replace '^MYSQL_ROOT_PASSWORD=', ''
if ([string]::IsNullOrWhiteSpace($password)) {
  Write-Host '[FAIL] 读不到 MYSQL_ROOT_PASSWORD（容器环境变量缺失）' -ForegroundColor Red
  exit 1
}

$sql = @'
SELECT 'bad_total', COUNT(*) FROM alert a
JOIN task_supplier ts ON ts.id = a.task_supplier_id
JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
JOIN supplier s ON s.id = a.supplier_id AND s.code LIKE 'DEMO-%'
WHERE a.status IN ('UNREAD', 'HANDLED') AND a.type = 'TOTAL_LOW'
  AND (ts.total_score IS NULL OR ts.total_score >= 70)
UNION ALL
SELECT 'bad_key', COUNT(*) FROM alert a
JOIN task_supplier ts ON ts.id = a.task_supplier_id
JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
JOIN supplier s ON s.id = a.supplier_id AND s.code LIKE 'DEMO-%'
WHERE a.status IN ('UNREAD', 'HANDLED') AND a.type = 'KEY_DIMENSION_LOW'
  AND NOT EXISTS (
    SELECT 1 FROM task_supplier_dimension_result r
    JOIN dimension d ON d.id = r.dimension_id AND d.is_key = 1
    WHERE r.task_supplier_id = a.task_supplier_id AND r.score < 70
  )
UNION ALL
SELECT 'bad_trend', COUNT(*) FROM alert a
JOIN task_supplier ts ON ts.id = a.task_supplier_id
JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
JOIN supplier s ON s.id = a.supplier_id AND s.code LIKE 'DEMO-%'
WHERE a.status IN ('UNREAD', 'HANDLED') AND a.type = 'TREND_DECLINE'
UNION ALL
SELECT 'bad_total_weight', COUNT(*) FROM (
  SELECT ts.id
  FROM task_supplier ts
  JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
  JOIN supplier s ON s.id = ts.supplier_id AND s.code LIKE 'DEMO-%'
  JOIN (
    SELECT r.task_supplier_id, ROUND(SUM(r.score * r.weight) / 100, 2) AS total
    FROM task_supplier_dimension_result r
    GROUP BY r.task_supplier_id
  ) x ON x.task_supplier_id = ts.id
  WHERE ts.status = 'EFFECTIVE' AND ABS(ts.total_score - x.total) > 0.01
) z
UNION ALL
SELECT 'bad_qual_score', COUNT(*) FROM score sc
JOIN sheet sh ON sh.id = sc.sheet_id
JOIN task_supplier ts ON ts.id = sh.task_supplier_id
JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
JOIN supplier s ON s.id = ts.supplier_id AND s.code LIKE 'DEMO-%'
JOIN indicator i ON i.id = sc.indicator_id
WHERE i.type = 'QUAL' AND ABS(sc.score_value - sc.raw_value * 20) > 0.01
UNION ALL
SELECT 'bad_handled', COUNT(*) FROM alert a
JOIN supplier s ON s.id = a.supplier_id AND s.code LIKE 'DEMO-%'
WHERE a.status = 'HANDLED' AND a.handled_by IS NULL
UNION ALL
SELECT 'demo_alerts', COUNT(*) FROM alert a
JOIN supplier s ON s.id = a.supplier_id AND s.code LIKE 'DEMO-%'
UNION ALL
SELECT 'active_alerts', COUNT(*) FROM alert a
JOIN supplier s ON s.id = a.supplier_id AND s.code LIKE 'DEMO-%'
WHERE a.status IN ('UNREAD', 'HANDLED');
'@

$output = docker exec -e "MYSQL_PWD=$password" $Container mysql -uroot -N -B $Database -e $sql 2>&1
if ($LASTEXITCODE -ne 0) {
  Write-Host "[FAIL] 查询失败：$output" -ForegroundColor Red
  exit 1
}

$expected = @{
  bad_total       = 0
  bad_key         = 0
  bad_trend       = 0
  bad_total_weight = 0
  bad_qual_score  = 0
  bad_handled     = 0
  demo_alerts     = 5
  active_alerts   = 4
}

$failures = @()
$values = @{}
foreach ($line in ($output -split "`n")) {
  $text = $line.Trim()
  if ([string]::IsNullOrWhiteSpace($text)) { continue }
  $parts = $text -split "`t"
  if ($parts.Count -ne 2) { continue }
  $name = $parts[0]
  $value = 0
  if (-not [int]::TryParse($parts[1], [ref]$value)) { continue }
  $values[$name] = $value
  if (-not $expected.ContainsKey($name)) { continue }
  if ($value -ne $expected[$name]) {
    $failures += "$name=$value（期望 $($expected[$name])）"
  }
}

foreach ($name in $expected.Keys) {
  if (-not $values.ContainsKey($name)) {
    $failures += "$name 未返回（查询缺失）"
  }
}

if ($failures.Count -gt 0) {
  Write-Host ("[FAIL] 演示数据不自洽：" + ($failures -join '；')) -ForegroundColor Red
  exit 1
}

Write-Host '[OK] 演示数据自洽：预警与分数、总分与维度加权和、定性 raw→score 全部一致' -ForegroundColor Green

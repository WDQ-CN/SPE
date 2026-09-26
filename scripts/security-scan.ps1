#Requires -Version 5.1
<#
.SYNOPSIS
  SPE 供应链漏洞核对：前端依赖 + 后端依赖 + 本地镜像。

.DESCRIPTION
  对应 06 §8 第 19 条与 03 §20。三条设计约束：

    1. **工具不入库**：trivy 二进制与它的漏洞库都放在 %TEMP%\spe-tools 下，随用随下
       （体积大、时效性强，入库只会让仓库变胖且迅速过期）。
    2. **可复现**：trivy 版本由参数固定，默认值写在 param 里；扫描日期与版本要一并记进 03 §19，
       否则「上次扫过」这个结论无法复算。
    3. **不假装**：任何一步失败都如实打印失败与原因，并以非零退出码结束——
       「没扫过」必须一眼看得出来，不允许静默退化成「没问题」（03 §16.1 的 ROB-1 就是这么来的）。

.PARAMETER TrivyVersion
  trivy 版本（形如 v0.74.0）。换版本时请同步更新 03 §19 里记录的版本号。

.PARAMETER SkipImages
  跳过镜像扫描（镜像扫描最慢，只改依赖时可跳过）。

.PARAMETER SkipFrontend
  跳过前端 pnpm audit。

.EXAMPLE
  pwsh scripts/security-scan.ps1
  pwsh scripts/security-scan.ps1 -SkipImages

.NOTES
  为什么扫「打包后的 fat jar」而不是 pom.xml：pom 只列直接依赖，而漏洞绝大多数藏在传递依赖里；
  fat jar 的 BOOT-INF/lib 才是真正会被部署的那份清单。

  为什么不用 OWASP dependency-check（06 §8 原文点名的另一个选择）：它首次要下载 NVD 全量数据，
  而无 API key 时 NVD 限速 5 请求/30 秒，叠加本机网络延迟（实测 NVD 单请求 ~12s），一次扫描不现实。
  本脚本用 trivy（数据源含 NVD 收录的 OS 包与语言包漏洞）+ 前端 pnpm audit 覆盖同样的目标。
#>
[CmdletBinding()]
param(
  [string]$TrivyVersion = 'v0.74.0',
  [switch]$SkipImages,
  [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$toolDir = Join-Path $env:TEMP 'spe-tools'
$trivy = Join-Path $toolDir 'trivy.exe'
$reportPath = Join-Path $toolDir 'scan-report.txt'
$script:failures = @()

function Section([string]$title) {
  $line = "`n=== $title ==="
  Write-Host $line -ForegroundColor Cyan
  Add-Content -Path $reportPath -Value $line -Encoding UTF8
}

function Note([string]$text, [string]$color = 'Gray') {
  Write-Host $text -ForegroundColor $color
  Add-Content -Path $reportPath -Value $text -Encoding UTF8
}

function Fail([string]$text) {
  Note "  !! $text" 'Red'
  $script:failures += $text
}

New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
Set-Content -Path $reportPath -Value "SPE 供应链漏洞扫描报告`n时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`ntrivy 版本: $TrivyVersion" -Encoding UTF8

# pnpm 必须经 corepack 调用（见 CLAUDE.md：PATH 上的 pnpm.cmd 是坏垫片）；corepack 本身也可能不在 PATH 上
$corepack = (Get-Command corepack -ErrorAction SilentlyContinue).Source
if (-not $corepack) { $corepack = 'D:\Node\corepack.cmd' }

# ---------------------------------------------------------------- 前端依赖
if (-not $SkipFrontend) {
  Section '前端依赖（pnpm audit）'
  Push-Location (Join-Path $root 'frontend')
  try {
    $audit = & $corepack pnpm audit --json 2>&1 | Out-String
    try {
      $json = $audit | ConvertFrom-Json
      $v = $json.metadata.vulnerabilities
      Note ("  info={0} low={1} moderate={2} high={3} critical={4}" -f $v.info, $v.low, $v.moderate, $v.high, $v.critical)
      if ($v.high -gt 0 -or $v.critical -gt 0) {
        Fail "前端存在 high/critical 漏洞，需处置后再收口"
        $json.advisories.PSObject.Properties | ForEach-Object {
          $a = $_.Value
          if ($a.severity -in @('high', 'critical')) {
            Note ("    [$($a.severity)] $($a.module_name) $($a.vulnerable_versions) → 修复版本 $($a.patched_versions)") 'Yellow'
          }
        }
      }
    } catch {
      Note "  pnpm audit 输出无法解析（可能是 registry 不可达或版本差异）：" 'Yellow'
      Note ($audit -split "`n" | Select-Object -First 5 | Out-String)
      Fail '前端审计未完成'
    }
  } finally {
    Pop-Location
  }
}

# ---------------------------------------------------------------- trivy 二进制
Section 'trivy 准备'
if (-not (Test-Path $trivy)) {
  Note "  本地没有 trivy，开始下载 $TrivyVersion（约 60MB，放到 $toolDir）"
  $zip = Join-Path $toolDir 'trivy.zip'
  $url = "https://github.com/aquasecurity/trivy/releases/download/$TrivyVersion/trivy_$($TrivyVersion.TrimStart('v'))_windows-64bit.zip"
  try {
    & curl.exe -sL --max-time 900 -o $zip $url
    if (-not (Test-Path $zip)) { throw '下载后文件不存在' }
    Expand-Archive -Path $zip -DestinationPath $toolDir -Force
  } catch {
    Fail "trivy 下载/解压失败：$($_.Exception.Message)。可手工下载 $url 解压到 $toolDir 后重跑。"
  }
}
if (Test-Path $trivy) {
  Note "  $(& $trivy --version 2>&1 | Select-Object -First 1)"
} else {
  Fail 'trivy 不可用，后端依赖与镜像扫描跳过（**这不是"没有漏洞"，是"没有核对"**）'
}

# ---------------------------------------------------------------- 后端依赖（扫 fat jar）
if (Test-Path $trivy) {
  Section '后端依赖（trivy 扫 fat jar 的 BOOT-INF/lib）'
  Push-Location (Join-Path $root 'backend')
  try {
    Note '  先打包（跳过测试）以产出含全部运行时依赖的 fat jar…'
    & .\mvnw.cmd -o package -DskipTests -q 2>&1 | Out-Null
    $jar = Get-ChildItem 'target\spe-backend-*.jar' -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*original*' } |
      Select-Object -First 1
    if (-not $jar) {
      Fail '没有找到 fat jar，后端依赖扫描未完成'
    } else {
      Note "  扫描 $($jar.Name)（$([math]::Round($jar.Length / 1MB, 1)) MB）"
      $out = & $trivy fs --scanners vuln --severity 'CRITICAL,HIGH' --timeout 30m --quiet $jar.FullName 2>&1 | Out-String
      ($out -split "`n") | ForEach-Object { Add-Content -Path $reportPath -Value $_ -Encoding UTF8 }
      Write-Host $out
      if ($out -match 'Total:\s*(\d+)') { Note "  高危及以上命中：$($matches[1])" }
    }
  } catch {
    Fail "后端依赖扫描失败：$($_.Exception.Message)"
  } finally {
    Pop-Location
  }
}

# ---------------------------------------------------------------- 镜像
if ((Test-Path $trivy) -and -not $SkipImages) {
  Section '基础镜像与自建镜像（trivy image）'
  # 自建镜像先重建，保证扫的是当前代码（否则结论对应的是旧产物）
  foreach ($image in @('specs-backend:latest', 'specs-nginx:latest', 'nginx:1.30-alpine', 'mysql:8.4')) {
    # 只扫本地已有的镜像：本机 Docker Hub 不通，缺的镜像不去拉（拉不到会卡很久）
    $exists = docker image inspect $image 2>$null
    if (-not $exists) {
      Note "  跳过 $image（本地没有该镜像，本机无法从 Docker Hub 拉取）" 'Yellow'
      continue
    }
    Note "`n  --- $image ---"
    $out = & $trivy image --scanners vuln --severity 'CRITICAL,HIGH' --timeout 30m --quiet $image 2>&1 | Out-String
    ($out -split "`n") | ForEach-Object { Add-Content -Path $reportPath -Value $_ -Encoding UTF8 }
    Write-Host $out
  }
}

# ---------------------------------------------------------------- 汇总
Section '汇总'
Note "  完整报告：$reportPath"
if ($script:failures.Count -gt 0) {
  Note "  未完成的项（$($script:failures.Count)）：" 'Red'
  $script:failures | ForEach-Object { Note "    - $_" 'Red' }
  Note '  结论：**仍有未核对的部分**，不要当成「没有问题」。' 'Red'
  exit 1
}
Note '  所有扫描步骤都已完成。请把「日期 + trivy 版本 + 高危命中数」记进 03 §19。' 'Green'
exit 0

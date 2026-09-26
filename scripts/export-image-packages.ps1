#Requires -Version 5.1
<#
.SYNOPSIS
  导出待核对镜像的 OS 标识与已安装包清单（供 scripts/osv-image-scan.mjs 查 OSV）。

.DESCRIPTION
  为什么单独一步、而且用 PowerShell 而不是让 Node 直接调 docker：**Node 的子进程在本机调不动 docker**
  （npipe 访问受限，连 stderr 都拿不到，只报 "Command failed"），而 PowerShell 侧一切正常。
  所以分工是——PowerShell 负责「进容器取事实」，Node 负责「拿清单查 OSV」（03 §20.5）。

  产物写到 %TEMP%\spe-image-packages\<镜像名>.json，每份形如：
    { "image": "...", "id": "alpine", "versionId": "3.24.2", "prettyName": "...",
      "manager": "apk", "packages": [ { "name": "musl", "version": "1.2.5-r0" }, ... ] }

  只实现 apk 一种包管理器：本项目当前的镜像里只有 Alpine 与 Oracle Linux，而 **OSV 不覆盖
  Oracle Linux**（那类要看厂商 errata），所以 rpm/dpkg 分支现在没有可用的下游消费者——
  与其写一段没人验证过的代码，不如让下游脚本明确报告「该生态未核对」。

.PARAMETER Images
  要导出的镜像，默认是 compose 里的三个自建/基础镜像加 mysql。

.EXAMPLE
  .\scripts\export-image-packages.ps1
#>
[CmdletBinding()]
param(
  [string[]]$Images = @('specs-backend:latest', 'specs-nginx:latest', 'nginx:1.30-alpine', 'mysql:8.4')
)

$ErrorActionPreference = 'Stop'
$outDir = Join-Path $env:TEMP 'spe-image-packages'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

foreach ($image in $Images) {
  Write-Host "--- $image ---"

  $osText = docker run --rm --entrypoint cat $image /etc/os-release 2>$null
  if (-not $osText) {
    Write-Host '  读不到 /etc/os-release，跳过（镜像可能不存在）'
    continue
  }

  $os = @{}
  foreach ($line in $osText) {
    if ($line -match '^([A-Z_]+)=(.*)$') {
      $os[$matches[1]] = $matches[2].Trim('"')
    }
  }

  $manager = $null
  $packages = @()
  if ($os['ID'] -eq 'alpine') {
    $manager = 'apk'
    # `apk info -v` 每行形如 `musl-1.2.5-r0`；包名本身可能含 `-`，所以从行尾的版本后缀切
    foreach ($line in (docker run --rm --entrypoint apk $image info -v 2>$null)) {
      if ($line -match '^(.+)-([0-9][^-]*-r[0-9]+)$') {
        $packages += [ordered]@{ name = $matches[1]; version = $matches[2] }
      }
    }
  }

  $record = [ordered]@{
    image      = $image
    id         = $os['ID']
    versionId  = $os['VERSION_ID']
    prettyName = $os['PRETTY_NAME']
    manager    = $manager
    packages   = $packages
  }

  $target = Join-Path $outDir (($image -replace '[:/]', '_') + '.json')
  # 手写无 BOM 的 UTF-8：PS 5.1 的 `Set-Content -Encoding UTF8` 会加 BOM，Node 侧 JSON.parse 会直接失败
  [System.IO.File]::WriteAllText($target, ($record | ConvertTo-Json -Depth 6), $utf8NoBom)
  Write-Host "  写出 $target（管理器=$manager，包数=$($packages.Count)）"
}

Write-Host ''
Write-Host "清单目录：$outDir"

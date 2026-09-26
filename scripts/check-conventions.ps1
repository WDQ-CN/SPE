#Requires -Version 5.1
<#
.SYNOPSIS
  把 CLAUDE「默认约定」里能机械化的部分，变成「违反就红」。

.DESCRIPTION
  对应 06 §8 第 22~26 条与 03 §22/§23。落地的四项检查：

    1. **`.ps1` 必须存 UTF-8 with BOM**——04-012 与 048 是同一根因、犯了两次，最该机械化的一条；
    2. **`.md`/`.java`/`.yml` 等文本文件不应带 BOM**——修第 1 项时别顺手给它们也加上；
    3. **`.ps1` 里不用 `Get-Content` 读文件内容**——04-019 第二次犯后的补救。只警告不失败：
       读纯 ASCII 的临时文件时它无害，一刀切会把人逼去绕开检查；
    4. **文档编号与交叉引用自洽**——04 的条目号、03 的章节号无缺号无重复，且**别处引用的编号真的存在**
       （含 `04-0NN`、`03 §N`、`§8 第 N 条`）。

  第 4 项来自本次整理暴露的真实风险：文档里到处是「03 §22」「04-051」「06 §8 第 25 条」这类引用，
  而 06 §8 第 21 条自己就写着"本清单自身是否仍然准确（例：新增条款后旧编号有没有重复）"。
  这种事靠人靠不住——04 的复发机制执行率 1/50 就是前车之鉴（03 §22）——交给脚本。

.PARAMETER Quiet
  只输出失败与警告（收了给别的脚本用）。

.EXAMPLE
  .\scripts\check-conventions.ps1
  .\scripts\check-conventions.ps1 -Quiet

.NOTES
  为什么读文件一律用 `[System.IO.File]::ReadAllText/ReadAllBytes` 而不是 `Get-Content`：
  本脚本要检查的正是"别用 Get-Content"，自己自然也不能用；04-019 的乱码教训本来就源于按错误编码读文件。
  同理，下面查 `Get-Content` 用的搜索词是**拼接**出来的，免得脚本报自己。

  这一层检查的定位：**不是替代评审，而是兜住那些"说了一百遍还是会忘"的机械事项**。
  判断一条约定该不该机械化，就问 06 §8 第 25 条那句——"违反时谁会拦住我"。
#>
[CmdletBinding()]
param(
  [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$script:failures = @()
$script:warnings = @()

function Say([string]$text, [string]$color = 'Gray') {
  if (-not $Quiet -or $color -ne 'Gray') { Write-Host $text -ForegroundColor $color }
}
function Pass([string]$name, [string]$detail) { Say "  [OK]   $name $detail" 'Green' }
function Fail([string]$name, [string]$detail) {
  Say "  [FAIL] $name $detail" 'Red'
  $script:failures += "$name :: $detail"
}
function Warn([string]$name, [string]$detail) {
  Say "  [WARN] $name $detail" 'Yellow'
  $script:warnings += "$name :: $detail"
}
function Rel([string]$full) { return $full.Replace($root + '\', '') }

# 只扫手写的文件：产物与依赖目录一律不看
$excluded = '\\(\.git|node_modules|target|dist|test-results|archunit_store|\.idea)\\'
function RepoFiles([string]$filter) {
  Get-ChildItem -Path $root -Recurse -File -Filter $filter |
    Where-Object { $_.FullName -notmatch $excluded }
}

# ---------------------------------------------------------------- 1) .ps1 必须有 BOM
Say ''
Say '=== 1) PowerShell 脚本编码（04-012 / 04-048）==='
$psFiles = @(RepoFiles '*.ps1')
$badBom = 0
foreach ($file in $psFiles) {
  $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
  $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
  if (-not $hasBom) {
    Fail 'ps1-bom' "$(Rel $file.FullName) 缺 BOM —— PS 5.1 会把中文注释读成乱码，然后报一堆对不上行号的语法错"
    $badBom += 1
  }
}
if ($badBom -eq 0) {
  Pass 'ps1-bom' "（$($psFiles.Count) 个 .ps1 全部带 BOM）"
}

# ---------------------------------------------------------------- 2) 其它文本不应带 BOM
Say ''
Say '=== 2) 其它文本文件不应带 BOM（Java/Maven/Git 一侧的惯例）==='
$textFiles = @()
foreach ($pattern in @('*.md', '*.yml', '*.yaml', '*.java', '*.ts', '*.vue', '*.mjs', '*.json', '*.sql')) {
  $textFiles += RepoFiles $pattern
}
$textFiles = $textFiles | Sort-Object FullName -Unique
$withBom = @()
foreach ($file in $textFiles) {
  $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
  if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
    $withBom += $file
  }
}
if ($withBom.Count -eq 0) {
  Pass 'text-no-bom' "（$($textFiles.Count) 个文本文件均无 BOM）"
} else {
  foreach ($file in $withBom) { Fail 'text-no-bom' "$(Rel $file.FullName) 带了 BOM（只有 .ps1 需要 BOM）" }
}

# ---------------------------------------------------------------- 3) 脚本里别用 Get-Content 读内容
# 搜索词与报告文本都用拼接：本脚本的注释与说明里必然要提到它，写死字面量就会自己报自己
$needle = 'Get-' + 'Content'
Say ''
Say "=== 3) 脚本里是否用 $needle 读文件内容（04-019 的补救）==="
$scriptHits = @()
foreach ($file in $psFiles) {
  $lines = [System.IO.File]::ReadAllText($file.FullName, $utf8NoBom) -split "`r?`n"
  $inBlockComment = $false
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    $trimmed = $line.TrimStart()
    # 块注释 <# ... #> 里的提及不算（本脚本的说明段就是这种）
    if ($inBlockComment) {
      if ($line.Contains('#>')) { $inBlockComment = $false }
      continue
    }
    if ($trimmed.StartsWith('<#')) {
      if (-not $line.Contains('#>')) { $inBlockComment = $true }
      continue
    }
    if ($trimmed.StartsWith('#')) { continue }   # 行注释里提到不算
    if ($line.Contains($needle)) { $scriptHits += "$(Rel $file.FullName):$($i + 1)" }
  }
}
if ($scriptHits.Count -eq 0) {
  Pass 'no-get-content' '（没有脚本用它读内容）'
} else {
  Warn 'no-get-content' "发现 $($scriptHits.Count) 处：$($scriptHits -join ', ') —— 读文件内容/统计行数请用专用读工具或 [System.IO.File]::ReadAllText（读纯 ASCII 临时文件时可忽略本警告）"
}

# ---------------------------------------------------------------- 4) 文档编号与交叉引用
Say ''
Say '=== 4) 文档编号与交叉引用自洽（06 §8 第 21 条要的"本清单自身是否准确"）==='

function DocLines([string]$path) {
  return [System.IO.File]::ReadAllText($path, $utf8NoBom) -split "`r?`n"
}

function CollectNumbers([string[]]$lines, [string]$pattern) {
  $found = @()
  foreach ($line in $lines) {
    if ($line -match $pattern) { $found += [int]$matches[1] }
  }
  return $found
}

function CheckSequence([string]$name, [int[]]$numbers, [string]$shape) {
  if ($numbers.Count -eq 0) { Fail $name "一个编号都没找到（格式变了？期望 $shape）"; return }
  $dupes = @($numbers | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
  if ($dupes.Count -gt 0) { Fail $name "编号重复：$($dupes -join ', ')" }
  $max = ($numbers | Measure-Object -Maximum).Maximum
  $missing = @(1..$max | Where-Object { $numbers -notcontains $_ })
  if ($missing.Count -gt 0) {
    # 只列前 12 个：真出了大范围缺号，全部列出来反而看不清最该看的头部
    $shown = ($missing | Select-Object -First 12 | ForEach-Object { $_.ToString('D3') }) -join ', '
    $more = if ($missing.Count -gt 12) { " …共 $($missing.Count) 个" } else { '' }
    Fail $name "缺号：$shown$more"
  }
  if ($dupes.Count -eq 0 -and $missing.Count -eq 0) { Pass $name "（1~$max 连续，共 $($numbers.Count) 条）" }
}

$doc04 = Join-Path $root '04-开发小记.md'
$doc03 = Join-Path $root '03-文档评审报告.md'
$doc06 = Join-Path $root '06-开发规范.md'

$lines04 = DocLines $doc04
$lines03 = DocLines $doc03
$lines06 = DocLines $doc06

$nums04 = CollectNumbers $lines04 '^## (\d{3}) '
$nums03 = CollectNumbers $lines03 '^## (\d+)\. '

# 06 的自查清单条目要从 §8 起的范围内找（文件里别的编号不属于本清单）
$nums06 = @()
$inSec8 = $false
foreach ($line in $lines06) {
  if ($line -match '^## 8\. ') { $inSec8 = $true; continue }
  if ($inSec8 -and $line -match '^## ') { break }
  if ($inSec8 -and $line -match '^(\d+)\. ') { $nums06 += [int]$matches[1] }
}

CheckSequence 'doc04-编号' $nums04 '## 051 标题'
CheckSequence 'doc03-章节' $nums03 '## 22. 标题'
CheckSequence 'doc06-自查项' $nums06 'N. 条目（§8 范围内）'

# 交叉引用：引用的编号必须存在
$mdFiles = @(RepoFiles '*.md') | Where-Object { $_.DirectoryName -eq $root }
$badRefs = @()
foreach ($file in $mdFiles) {
  $lines = DocLines $file.FullName
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    foreach ($m in [regex]::Matches($line, '04-(\d{3})')) {
      if ($nums04 -notcontains [int]$m.Groups[1].Value) {
        $badRefs += "$(Rel $file.FullName):$($i + 1) 引用了 04-$($m.Groups[1].Value)，但该条目不存在"
      }
    }
    foreach ($m in [regex]::Matches($line, '03 §(\d+)')) {
      if ($nums03 -notcontains [int]$m.Groups[1].Value) {
        $badRefs += "$(Rel $file.FullName):$($i + 1) 引用了 03 §$($m.Groups[1].Value)，但该章节不存在"
      }
    }
    foreach ($m in [regex]::Matches($line, '§8 第 (\d+) 条')) {
      if ($nums06 -notcontains [int]$m.Groups[1].Value) {
        $badRefs += "$(Rel $file.FullName):$($i + 1) 引用了 06 §8 第 $($m.Groups[1].Value) 条，但该条目不存在"
      }
    }
  }
}
if ($badRefs.Count -eq 0) {
  Pass 'cross-refs' "（$($mdFiles.Count) 份根目录文档，引用的 04/03/06 编号全部存在）"
} else {
  foreach ($ref in $badRefs) { Fail 'cross-refs' $ref }
}

# ---------------------------------------------------------------- 5) 安全红线（06 §2 / 审查维度第 1 条）
Say ''
Say '=== 5) 安全红线：SQL 拼接与前端危险 API ==='

# 后端主代码：MyBatis 里 ${} 会把参数直接拼进 SQL，必须用 #{} 参数化
$javaFiles = @(RepoFiles '*.java') | Where-Object { $_.FullName -match '\\backend\\src\\main\\' }
$sqlHits = @()
foreach ($file in $javaFiles) {
  $lines = [System.IO.File]::ReadAllText($file.FullName, $utf8NoBom) -split "`r?`n"
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $t = $lines[$i].TrimStart()
    if ($t.StartsWith('*') -or $t.StartsWith('//') -or $t.StartsWith('/*')) { continue }
    if ($lines[$i].Contains('${')) { $sqlHits += "$(Rel $file.FullName):$($i + 1)" }
  }
}
if ($sqlHits.Count -eq 0) {
  Pass 'no-sql-interpolation' ('（' + $javaFiles.Count + ' 个后端主代码文件里没有 ${} 拼接）')
} else {
  foreach ($hit in $sqlHits) { Fail 'no-sql-interpolation' ($hit + ' 出现 ${} —— MyBatis 会把它直接拼进 SQL，必须用 #{} 参数化') }
}

# 前端：v-html 是 XSS 的直通门（FAIL）；innerHTML / eval( 只警告（清空节点之类的用法无害）
$feFiles = @()
foreach ($pattern in @('*.vue', '*.ts')) {
  $feFiles += (RepoFiles $pattern) | Where-Object { $_.FullName -match '\\frontend\\src\\' }
}
$vHtmlHits = @()
$otherHits = @()
foreach ($file in $feFiles) {
  $lines = [System.IO.File]::ReadAllText($file.FullName, $utf8NoBom) -split "`r?`n"
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $t = $lines[$i].TrimStart()
    if ($t.StartsWith('*') -or $t.StartsWith('//') -or $t.StartsWith('<!--')) { continue }
    if ($lines[$i].Contains('v-html')) { $vHtmlHits += "$(Rel $file.FullName):$($i + 1)" }
    foreach ($bad in @('innerHTML', 'eval(')) {
      if ($lines[$i].Contains($bad)) { $otherHits += "$(Rel $file.FullName):$($i + 1) -> $bad" }
    }
  }
}
if ($vHtmlHits.Count -eq 0) {
  Pass 'no-v-html' "（$($feFiles.Count) 个前端源文件里没有 v-html）"
} else {
  foreach ($hit in $vHtmlHits) { Fail 'no-v-html' "$hit 使用了 v-html —— 它把内容按 HTML 渲染，等于给 XSS 开门（03 §16 的 SEC-4）" }
}
if ($otherHits.Count -gt 0) {
  Warn 'fe-dangerous-api' "发现 $($otherHits.Count) 处：$($otherHits -join ', ') —— 清空节点之类的用法无害，但请确认不是渲染不可信内容"
} else {
  Pass 'fe-dangerous-api' '（没有 innerHTML / eval(）'
}

# ---------------------------------------------------------------- 汇总
Say ''
if ($script:failures.Count -gt 0) {
  Say "=== 结论：$($script:failures.Count) 项未通过 ===" 'Red'
  $script:failures | ForEach-Object { Say "  - $_" 'Red' }
  if ($script:warnings.Count -gt 0) { Say "（另有 $($script:warnings.Count) 条警告）" 'Yellow' }
  exit 1
}
if ($script:warnings.Count -gt 0) {
  Say "=== 结论：检查通过，但有 $($script:warnings.Count) 条警告 ===" 'Yellow'
  $script:warnings | ForEach-Object { Say "  - $_" 'Yellow' }
  exit 0
}
Say '=== 结论：全部通过 ===' 'Green'
exit 0

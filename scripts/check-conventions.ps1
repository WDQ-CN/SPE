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

  ⚠️ **每条检查前都有正例断言**（`RequireScanned`，见 04-070 / 04-071）：扫不到输入时判 `scan-empty`
  **失败**，而不是报「通过」。判据的形状必须是「**拿到 N 个正例且无反例**」，不是「**没有反例**」——
  后者的反面是"什么都没扫到"，而那恰恰是最常见的一种失败（目录改名、glob 失效、在别的 cwd 里跑）。
  没有这层断言时，整条检查会在扫不到文件时**静默变成一句 OK**、退出码 0。

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

# ⚠️ 正例断言（04-070 / 04-071）：本脚本每条检查都是「扫到东西 → 找反例 → 没反例就 OK」。
#    这个形状有个致命缺口：**什么都没扫到**时它同样报 OK、退出码 0。触发条件很低——glob 失效、
#    写法变了、在别的 cwd 里跑。所以每条检查前先断言"扫到了 N 个正例"，扫不到就判**不可信**（FAIL），
#    而不是"没问题"。判据的形状永远是「**拿到 N 个正例且无反例**」，不是「**没有反例**」。
#    ⚠️ 消息统一带 `scan-empty：` 前缀（与 check-test-quality.ps1 同名）——独立审查的 P2-2：早先只报原
#       检查名，于是 `grep scan-empty` 会**漏掉本脚本的全部守卫**，而 CLAUDE 里写的是"两个门禁…判 scan-empty"。
#    ⚠️ 对 `*.ps1` 这条断言**当前不可达**：脚本自身就是一个 .ps1 ⇒ `RepoFiles '*.ps1'` 至少扫到它，
#       `$psFiles.Count ≥ 1` 恒真（独立审查的 P2-5）。留它是防御性的，**别把它当成"活着的守卫"**。
function RequireScanned([string]$name, [int]$count, [string]$what) {
  if ($count -gt 0) { return $true }
  Fail $name "scan-empty：$what 一个都没扫到 —— 判定不可信（路径/glob 失效？），不代表没有问题"
  return $false
}

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
$ps1Scanned = RequireScanned 'ps1-bom' $psFiles.Count '.ps1 文件'
$badBom = 0
foreach ($file in $psFiles) {
  $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
  $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
  if (-not $hasBom) {
    Fail 'ps1-bom' "$(Rel $file.FullName) 缺 BOM —— PS 5.1 会把中文注释读成乱码，然后报一堆对不上行号的语法错"
    $badBom += 1
  }
}
if ($ps1Scanned -and $badBom -eq 0) {
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
$textScanned = RequireScanned 'text-no-bom' $textFiles.Count '文本文件（.md/.yml/.java/.ts/.vue/.sql…）'
$withBom = @()
foreach ($file in $textFiles) {
  $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
  if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
    $withBom += $file
  }
}
if ($textScanned -and $withBom.Count -eq 0) {
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
  # ⚠️ 这里必须**先看扫描是否有效**：早先写成 `if ($ps1Scanned -and $scriptHits.Count -eq 0) { Pass } else { Warn "发现 0 处…" }`，
  #    于是".ps1 一个都没扫到"时会走进 else、发出一条**误导性的「发现 0 处」警告**（既不是 OK 也不是 FAIL，
  #    读起来像"检查过了、有一处问题"）。这与本轮修的其它几处同源：**判据没区分"没有反例"和"没扫到东西"**（04-071）。
  if ($ps1Scanned) {
    Pass 'no-get-content' '（没有脚本用它读内容）'
  } else {
    Fail 'no-get-content' '.ps1 文件一个都没扫到 —— 这条检查没法判定（不是"没有脚本用它读内容"）'
  }
} else {
  Warn 'no-get-content' "发现 $($scriptHits.Count) 处：$($scriptHits -join ', ') —— 读文件内容/统计行数请用专用读工具或 [System.IO.File]::ReadAllText（读纯 ASCII 临时文件时可忽略本警告）"
}

# ---------------------------------------------------------------- 4) 文档编号与交叉引用
Say ''
Say '=== 4) 文档编号与交叉引用自洽（06 §8 第 21 条要的"本清单自身是否准确"）==='

function DocLines([string]$path) {
  # ⚠️ 文件不存在时返回空数组，而不是让 ReadAllText 抛异常（实测：探针树里没有 .md 时，本脚本
  #    直接抛 FileNotFoundException 崩掉，只留一段堆栈）。两者都是「失败」，但后者能告诉人**为什么**：
  #    「一个编号都没找到 → 判定不可信」（04-071）。
  if (-not (Test-Path $path)) { return @() }
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
$mdFiles = @(@(RepoFiles '*.md') | Where-Object { $_.DirectoryName -eq $root })
$mdScanned = RequireScanned 'cross-refs' $mdFiles.Count '根目录 .md 文档'
$badRefs = @()
foreach ($file in $mdFiles) {
  $lines = DocLines $file.FullName
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    # ⚠️ 正则**必须带边界**（2026-09-26 实测，03 §31.6）：早先写的是 `'04-(\d{3})'`——
    #    于是**行号范围**里的子串（形如「04 紧接三位数字」，例如把源码位置写成 `:304-308`）
    #    会被当成一次交叉引用 ⇒ **假阳性**。而且它不是偶发：只要"数字里出现 04+三位数"就触发，
    #    连**描述这个缺陷的文档本身**都会触发（实测：写 §31.6 时同一次运行 FAIL 从 1 条涨到 7 条）。
    #    假发现比不检查更糟——它训练人忽略红灯。左边界 `(?<!\d)` 挡"数字尾部"，右边界 `(?!\d)` 挡更长数字。
    foreach ($m in [regex]::Matches($line, '(?<!\d)04-(\d{3})(?!\d)')) {
      if ($nums04 -notcontains [int]$m.Groups[1].Value) {
        $badRefs += "$(Rel $file.FullName):$($i + 1) 引用了 04-$($m.Groups[1].Value)，但该条目不存在"
      }
    }
    # 同源：`03 §N` 也要边界，否则「第 103 §5」这种写法会被当成 `03 §5`。
    foreach ($m in [regex]::Matches($line, '(?<!\d)03 §(\d+)(?!\d)')) {
      if ($nums03 -notcontains [int]$m.Groups[1].Value) {
        $badRefs += "$(Rel $file.FullName):$($i + 1) 引用了 03 §$($m.Groups[1].Value)，但该章节不存在"
      }
    }
    # ⚠️ 本条**没有锚定文档**（原型是 `§8 第 (\d+) 条`）：若别处出现「01 §8 第 N 条」，也会被拿去和 **06** 的条目比。
    #    实测现状：全仓 27 处「§8 第 N 条」**全部**确指 06 §8 ⇒ **当前无误报**。
    #    ⚠️ **本轮刻意不改它的语义**：收紧成 `06 §8 第…` 会让"裸写 §8 第 N 条"不再被检查 = 引入**假阴性**。
    #    要收紧请先定口径（是"只检查写明 06 的"还是"裸写也必须指 06"）。
    foreach ($m in [regex]::Matches($line, '§8 第 (\d+) 条')) {
      if ($nums06 -notcontains [int]$m.Groups[1].Value) {
        $badRefs += "$(Rel $file.FullName):$($i + 1) 引用了 06 §8 第 $($m.Groups[1].Value) 条，但该条目不存在"
      }
    }
  }
}
if ($mdScanned -and $badRefs.Count -eq 0) {
  Pass 'cross-refs' "（$($mdFiles.Count) 份根目录文档，引用的 04/03/06 编号全部存在）"
} else {
  foreach ($ref in $badRefs) { Fail 'cross-refs' $ref }
}

# ---------------------------------------------------------------- 5) 安全红线（06 §2 / 审查维度第 1 条）
Say ''
Say '=== 5) 安全红线：SQL 拼接与前端危险 API ==='

# 后端主代码：MyBatis 里 ${} 会把参数直接拼进 SQL，必须用 #{} 参数化
$javaFiles = @(@(RepoFiles '*.java') | Where-Object { $_.FullName -match '\\backend\\src\\main\\' })
$javaScanned = RequireScanned 'no-sql-interpolation' $javaFiles.Count '后端主代码 .java'
$sqlHits = @()
foreach ($file in $javaFiles) {
  $lines = [System.IO.File]::ReadAllText($file.FullName, $utf8NoBom) -split "`r?`n"
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $t = $lines[$i].TrimStart()
    if ($t.StartsWith('*') -or $t.StartsWith('//') -or $t.StartsWith('/*')) { continue }
    if ($lines[$i].Contains('${')) { $sqlHits += "$(Rel $file.FullName):$($i + 1)" }
  }
}
if ($javaScanned -and $sqlHits.Count -eq 0) {
  Pass 'no-sql-interpolation' ('（' + $javaFiles.Count + ' 个后端主代码文件里没有 ${} 拼接）')
} else {
  foreach ($hit in $sqlHits) { Fail 'no-sql-interpolation' ($hit + ' 出现 ${} —— MyBatis 会把它直接拼进 SQL，必须用 #{} 参数化') }
}

# 前端：v-html 是 XSS 的直通门（FAIL）；innerHTML / eval( 只警告（清空节点之类的用法无害）
$feFiles = @()
foreach ($pattern in @('*.vue', '*.ts')) {
  $feFiles += (RepoFiles $pattern) | Where-Object { $_.FullName -match '\\frontend\\src\\' }
}
# ⚠️ 同 check-test-quality.ps1：对 `frontend\src` 是**无条件要求**（目录不在也算扫不到 → FAIL）。
#    独立审查的 P2-3 已就此定过：这是**有意的取舍**——"目录没了就让检查静默消失"正是本轮修掉的病，
#    宁可**响亮地红**。已知代价：backend-only 的稀疏检出会得到假 FAIL；将来要支持再区分两类情形。
$feScanned = RequireScanned 'no-v-html' $feFiles.Count '前端源文件（.vue/.ts）'
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
if ($feScanned -and $vHtmlHits.Count -eq 0) {
  Pass 'no-v-html' "（$($feFiles.Count) 个前端源文件里没有 v-html）"
} else {
  foreach ($hit in $vHtmlHits) { Fail 'no-v-html' "$hit 使用了 v-html —— 它把内容按 HTML 渲染，等于给 XSS 开门（03 §16 的 SEC-4）" }
}
if ($otherHits.Count -gt 0) {
  Warn 'fe-dangerous-api' "发现 $($otherHits.Count) 处：$($otherHits -join ', ') —— 清空节点之类的用法无害，但请确认不是渲染不可信内容"
} elseif ($feScanned) {
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

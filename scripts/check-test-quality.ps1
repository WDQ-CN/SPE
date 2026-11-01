<#
.SYNOPSIS
  测试质量的机械化检查（06 §8 第 29 条）。

.DESCRIPTION
  与 `check-conventions.ps1` 的分工：那个管「编码约定与安全红线」，这个管「测试本身的可信度」。
  背景：03 §17（测试体系审查）与 §25（W2 深审）两轮都发现，测试的问题从来不是「数量不够」，
  而是「看起来测了、其实测不到」——所以这里**不追求覆盖率数字**，只卡那些能机械判定的可信度信号。

  ⚠️ 断言正则必须包含 MockMvc 的 `andExpect`。本脚本第一版漏了它，于是把
  `AccountRevocationIntegrationTest` 与 `GlobalExceptionHandlerValidationTest` 误报成「断言数为 0」——
  而这两个类的断言其实很扎实（`andExpect(status())` + `jsonPath("$.code")`）。
  **度量工具不准会直接产出假发现**，改这条正则前先想清楚它会漏掉哪种写法（04-059）。

  检查项：
    1) 每个测试方法都要有 @DisplayName（失败时能看懂在测什么）
    1b) 不允许「有 @DisplayName 却没有 @Test」的方法（编译通过但永不执行，最坏的静默失效）
    2) 不允许 @Disabled / @Ignore（跳过的测试 = 隐藏在绿灯后面的缺口）
    3) Thread.sleep 要给出理由（时序脆弱点，必须能在失败时解释自己）
       ⚠️ 本条曾经**完全失效**：正则里含字面量 `sleep`，而"用了 sleep"本身就保证文本里有这个词，
       于是判据恒为假、分支永不触发。一个躺在检查项清单上、谁都没发现的**死检查**（04-071）。
    4) 断言密度（类级 < 1.0 → 警告）
    5) 测试里不得硬编码口令（凭据必须来自常量或配置）
    6) 前端 spec：用例数 vs expect 数（< 1.0 → 警告）
    7) 测试数为正但断言数为 0 的类 → 失败（最强信号）

  ⚠️ **每条检查都有正例断言**：扫不到输入时判 `scan-empty` **失败**，而不是报「通过」。
  判据的形状必须是「**拿到 N 个正例且无反例**」，不是「**没有反例**」——后者的反面是"什么都没扫到"，
  而那恰恰是最常见的一种失败（glob 失效、写法变了、在别的 cwd 里跑）。没有这层断言时，
  门禁会在扫不到文件时**静默退化成空操作**并 exit 0。

  ⚠️ **实测口径（04-071，别写成别的样子）**：用 `git show HEAD:scripts/…` 取出**改进前**的脚本，
  放进 `%TEMP%` 下一个**目录存在但没有匹配文件**的探针树里运行（**没有动真实目录**）：
    · 旧版：打印 `[OK] 没有发现可机械判定的问题` + `结论：通过（0 个 WARN）`，**退出码 0** —— 静默通过。
    · 新版：4 条 `scan-empty` FAIL，退出码 1。
    · 附注：若扫描**目录压根不存在**，旧版不是"通过"而是**抛异常崩掉**（`Get-ChildItem` 撞上
      `$ErrorActionPreference='Stop'`）。那同样是失败，只是看不出**为什么**失败。

  退出码：有 FAIL 时 1，否则 0。
#>

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

# ⚠️ 必须用 [System.IO.File]::ReadAllText 读文件内容，不用 Get-Content（04-019 / 06 §8 第 25 条）
$assertPat = 'assertThat|assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|' +
             'assertThrows|assertThatThrownBy|assertThatCode|assertDoesNotThrow|assertCatches|' +
             'fail\(|verify\(|verifyNoInteractions|andExpect|\.check\('

# ⚠️ `@Test` 必须带负向断言：否则会匹配注解族的兄弟 `@TestConfiguration` / `@Testcontainers`，于是基础设施类
# 被当成「有测试却无断言」的坏类（第一版就这样误报了 AbstractIntegrationTest 与 TestcontainersConfiguration）。
# ArchUnit 的规则用 `@ArchTest`，断言写在 `rule.check(...)` 里，所以上面也要收 `\.check\(`。
$testPat = '@Test(?![A-Za-z])|@ArchTest|@ParameterizedTest(?![A-Za-z])|@RepeatedTest(?![A-Za-z])'

# 用例声明正则：用**白名单修饰符**，而不是 `^\s*(it|test)\(`。
#   · 要收：`it(`、`it.each(`、`test.skip(`、`test.sequential(`（都是真的用例声明）
#   · 不收：`test.describe(`——那是分组，算成用例会把"分组数"混进"用例数"，density 就失去意义
# 早先漏掉 `it.each(`（属**潜在**缺陷，一并修掉）；`\.sequential` 是独立审查的 P2-4 补的：
# 白名单少一项，若某天用例全写成 `test.sequential(`，`$feIt` 会算成 0 而触发 **scan-empty 假 FAIL**。
$itPat = '(?m)^\s*(it|test)(\.each|\.skip|\.only|\.todo|\.fails|\.concurrent|\.sequential)?\('
$e2eItPat = '(?m)^\s*test(\.each|\.skip|\.only|\.todo|\.fails|\.concurrent|\.sequential)?\('

$fail = @()

function Add-ScanEmpty([string]$Message) {
  $script:fail += "scan-empty：$Message"
}
$warn = @()
$ok = @()

# ---------------------------------------------------------------- 后端

# ⚠️ fixture 只按**目录**排除：ArchUnit 的违规夹具全部放在 `fixture` 包里（实测 12 个，无一例外）。
#    早先还额外加了 `-Name -notlike '*fixture*'`，那是**按文件名**排除——实测全仓 0 个文件命中它，
#    也就是说它什么都没排除，却可能让 `ScoreFixtureTest.java` 这类**真测试被静默跳过**（04-071）。
$testDir = Join-Path $repoRoot 'backend\src\test'
$testFiles = @(Get-ChildItem -Recurse $testDir -Filter *.java -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -notmatch '[\\/]fixture[\\/]' })

# ⚠️ 正例断言（04-070 / 04-071）：判据的形状必须是「**拿到 N 个正例且无反例**」，不是「**没有反例**」——
#    后者的反面是"什么都没扫到"，而那恰恰是最常见的一种失败（目录改名、glob 失效、在别的 cwd 里跑）。
#    没有这一段时，扫不到任何测试文件会让本脚本**打印「结论：通过（0 个 WARN）」并 exit 0**：
#    门禁静默退化成空操作，比误报更难发现（误报至少会被人看见）。
if ($testFiles.Count -eq 0) {
  Add-ScanEmpty "在 $testDir 下一个 .java 都没扫到 —— 判定不可信（目录被移动/改名？），不代表没有问题"
}

$classStats = @()
foreach ($file in $testFiles) {
  $text = [System.IO.File]::ReadAllText($file.FullName)
  # ⚠️ 统计前**先剥掉注释**：源码注释里也会出现 `@DisplayName` / `Thread.sleep` / `@Test` 这些字面量，
  #    不剥就会产生**假发现**。W5 加 orphan-display-name 规则时立刻踩到：AuthServiceTest 里有一句注释
  #    写着「《@DisplayName》里…」，于是那条规则报了一个**根本不存在的**孤立注解（见 04-065）。
  #    这是 04-059「检查器不懂框架机制 → 误报」的同类，这次是不懂「注释不是代码」。
  $code = [regex]::Replace($text, '(?s)/\*.*?\*/', '')
  $code = [regex]::Replace($code, '(?m)//.*$', '')

  $tests = ([regex]::Matches($code, $testPat)).Count
  if ($tests -eq 0) { continue }

  $displayName = ([regex]::Matches($code, '@DisplayName')).Count
  $asserts = ([regex]::Matches($code, $assertPat)).Count
  $sleeps = ([regex]::Matches($code, 'Thread\.sleep')).Count
  $disabled = ([regex]::Matches($code, '@Disabled|@Ignore')).Count
  $relative = $file.FullName.Substring($repoRoot.Length + 1)

  # ⚠️ ArchUnit 是「字段型规则」，执行模型与 JUnit 不同，下面 display-name 与 no-assert 两项对它不适用：
  #   · JUnit 的 @DisplayName 目标不含 FIELD，加在 @ArchTest 字段上**编译不过**（试过：11 处编译错误
  #     「注解类型不适用于该类型的目标」）；规则的名字由字段名 + because("...") 承担，失败信息里本来就有。
  #   · 断言由框架执行，源码里不会出现 .check(...)，「没有断言」是假象。
  # 这是同一个坑的**第二次**（04-059）：检查器不懂框架机制 → 误报。加新检查项前先问一句
  # 「这条规则对每种测试模型都成立吗」。
  $isArchUnit = $code -match '@ArchTest'

  $classStats += [pscustomobject]@{
    路径 = $relative; 测试 = $tests; 断言 = $asserts; sleep = $sleeps
  }

  # 1) @DisplayName 覆盖率
  if ($displayName -lt $tests -and -not $isArchUnit) {
    $fail += "display-name：$relative 有 $tests 个测试方法但只有 $displayName 个 @DisplayName"
  }

  # 1b) @DisplayName 比测试方法还多 —— 说明有**孤立注解**：某个方法挂着 @DisplayName 却缺 @Test。
  #     这种方法的危害是「静默失效」的最坏形态：**编译通过、mvn test BUILD SUCCESS、检查器也不报**，
  #     只是那个用例永远不执行。W5 里真实发生过一次：我用 edit 在 @Test 上方插入新用例时，把锚定的
  #     @Test + @DisplayName 两行连同一起替换掉了，isKeyReallyMapsFromDatabase 就变成了普通方法；
  #     我是靠"用例数本该是 12"才发现（见 04-065）。
  if (-not $isArchUnit -and $displayName -gt $tests) {
    $fail += "orphan-display-name：$relative 有 $displayName 个 @DisplayName 但只统计到 $tests 个测试方法——有注解却缺 @Test 的方法不会被执行"
  }

  # 2) 跳过的测试
  if ($disabled -gt 0) {
    $fail += "disabled：$relative 有 $disabled 处 @Disabled/@Ignore（跳过的测试等于没测）"
  }

  # 3) Thread.sleep 需要理由。⚠️ 这一条**故意用 `$text` 而不是 `$code`**：理由本来就写在注释里，
  #    拿剥过注释的 `$code` 去查「有没有说明理由」永远查不到。
  # ⚠️⚠️ 但正则里**绝不能出现字面量 `sleep`**——这条检查曾经因此**完全失效**：`$sleeps -gt 0`
  #    已经蕴含 `$text` 里必然含 `Thread.sleep`，把 `sleep` 当"理由词"放进 `-notmatch`，判据就恒为假、
  #    该分支永不触发（一个"死检查"）。它躺在检查项清单第 3 条上很久，谁都没发现（04-071）。
  #    理由词只认**解释性**词汇，不认被测代码自身的名字。
  if ($sleeps -gt 0 -and $text -notmatch '时序|等待|说明|理由|为什么|因为') {
    $warn += "sleep：$relative 用了 Thread.sleep 但没有说明理由"
  }

  # 7) 有测试却完全没有断言 —— 最强信号（ArchUnit 除外，见上）
  if ($asserts -eq 0 -and -not $isArchUnit) {
    $fail += "no-assert：$relative 有 $tests 个测试方法，但一处断言/verify 都没有"
  }
  # 4) 断言密度过低（可能只测了「不抛异常」；ArchUnit 的断言不在源码里，同样豁免）
  elseif ($asserts -lt $tests -and -not $isArchUnit) {
    $warn += ("density：{0} 断言密度 {1:N2}（{2} 断言 / {3} 用例）" -f $relative, ($asserts / $tests), $asserts, $tests)
  }

  # 只盯「真会泄密」的两类：库口令与 JWT 密钥的字面量。演示用的种子口令（`DEFAULT_PASSWORD` /
  # `ADMIN_PASSWORD` 常量）是项目约定的一部分、本身公开，把它也报出来只会训练人忽略这条检查。
  if ($text -match '(DB_PASSWORD|DB_APP_PASSWORD|JWT_SECRET)\s*[=:]\s*"[^"]{8,}"') {
    $fail += "hardcoded-secret：$relative 里出现了库口令/密钥的字面量"
  }
}

if ($classStats.Count -eq 0 -and $testFiles.Count -gt 0) {
  Add-ScanEmpty "扫到了 $($testFiles.Count) 个 .java，但一个含 @Test/@ArchTest 的测试类都没有 —— 判定不可信"
}
if ($classStats.Count -gt 0) {
  $totalTests = ($classStats | Measure-Object 测试 -Sum).Sum
  $totalAsserts = ($classStats | Measure-Object 断言 -Sum).Sum
  $ok += ("backend-suite：{0} 个测试类 / {1} 个测试 / {2} 处断言（密度 {3:N2}）" -f `
    $classStats.Count, $totalTests, $totalAsserts, ($totalAsserts / $totalTests))
}

# ------------------------------------------------- ArchUnit 规则 ↔ 等效性用例

# 06 §5/§8 要求「逐条断言规则确实会命中违规，否则该规则等于没生效」。规则是**声明式对象**：
# 漏给一条规则配夹具不会报错，它只是安静地少了保护——所以这里机械比对每条 @ArchTest 规则名
# 是否都在 ArchUnitRulesAreEffectiveTest 里被引用过。这条检查抓出过真正漏掉的一条
# （controllersStayInOwnContext，11 条规则只覆盖了 10 条，见 03 §26）。
$archTestPath = Join-Path $repoRoot 'backend\src\test\java\com\spe\architecture\ArchitectureTest.java'
$archEffectPath = Join-Path $repoRoot 'backend\src\test\java\com\spe\architecture\ArchUnitRulesAreEffectiveTest.java'
# ⚠️ 正例断言：早先这里是 `if ((Test-Path A) -and (Test-Path B)) { … }`——**任一文件改名，整条检查就静默消失**，
#    而脚本照报「结论：通过」。检查项自己把自己关掉了，比它报错更难发现（04-071）。
$archMissing = @($archTestPath, $archEffectPath) | Where-Object { -not (Test-Path $_) }
if ($archMissing.Count -gt 0) {
  Add-ScanEmpty "ArchUnit 规则或等效性用例文件不存在（$($archMissing.Count) 个）—— 这条检查没法判定，不代表规则都有夹具"
} else {
  $ruleSource = [System.IO.File]::ReadAllText($archTestPath)
  $effectSource = [System.IO.File]::ReadAllText($archEffectPath)
  # ⚠️ 比对前先**剥掉注释**：`-notmatch [regex]::Escape($rule)` 只要规则名在文件里**出现过**就算覆盖，
  #    那么一句注释（"……参见 controllersStayInOwnContext 这条"）就能让规则被误判成"已覆盖"。
  #    这是 04-065「注释不是代码」的同一类，只是方向相反：那次是把注释**当成代码**，这次是**把注释当成证据**。
  $effectCode = [regex]::Replace($effectSource, '(?s)/\*.*?\*/', '')
  $effectCode = [regex]::Replace($effectCode, '(?m)//.*$', '')

  $rules = @([regex]::Matches($ruleSource, '(?m)^\s*static final ArchRule\s+(\w+)') |
    ForEach-Object { $_.Groups[1].Value })
  if ($rules.Count -eq 0) {
    Add-ScanEmpty "在 ArchitectureTest 里一个 `static final ArchRule` 都没扫到 —— 判定不可信（写法变了？）"
  }

  $uncovered = @()
  foreach ($rule in $rules) {
    if ($effectCode -notmatch [regex]::Escape($rule)) { $uncovered += $rule }
  }
  if ($rules.Count -gt 0 -and $uncovered.Count -gt 0) {
    $fail += ("arch-rule-uncovered：{0} 条 @ArchTest 规则没有等效性用例（漏配夹具的规则会静默空转）：{1}" -f `
      $uncovered.Count, ($uncovered -join ', '))
  }
  # ⚠️ `elseif ($rules.Count -gt 0)` 这个条件不能省（独立审查的 P2-1）：写成裸 `else` 时，若一条规则都没
  #    扫到，会在**同一屏**既打 `[FAIL] scan-empty…` 又打 `[OK] arch-rule-coverage：0 条 @ArchTest 规则
  #    全部有等效性用例`——后者落在"规模基线"区、内容是**空洞为真**，只读基线的读者会以为覆盖率已验证过。
  #    （退出码与 FAIL 行本身是对的，所以它不静默变绿，定 P2。）
  elseif ($rules.Count -gt 0) {
    $ok += ("arch-rule-coverage：{0} 条 @ArchTest 规则全部有等效性用例" -f $rules.Count)
  }
}

# ---------------------------------------------------------------- 前端

$feDir = Join-Path $repoRoot 'frontend\src'
# ⚠️ 不能写成 `if (Test-Path $feDir) { 整块 }`：那样**目录整个消失时这条检查也一起消失**，
#    而脚本照报「结论：通过」——检查项把自己关掉了，比它报错更难发现（04-071）。
#    改成"无条件执行 + 扫不到即判不可信"。
# ⚠️ **已知代价（独立审查的 P2-3，裁决为"有意选择"）**：对 `frontend\…` 是**无条件要求**，因此
#    backend-only 的稀疏检出（`git sparse-checkout`）或 CI 只 fetch `backend/` 时会得到 **假 FAIL**。
#    仍然选择它，因为"目录没了 → 检查静默消失"正是这一轮修掉的病；**响亮地红**优于安静地空转。
#    ⇒ 若将来真要支持那种检出，再区分"目录不存在（WARN）"与"存在但无文件（FAIL）"——别现在猜。
$feFiles = @()
if (Test-Path $feDir) {
  $feFiles = @(Get-ChildItem -Recurse $feDir -Include *.spec.ts -File -ErrorAction SilentlyContinue)
}
if ($feFiles.Count -eq 0) {
  Add-ScanEmpty "在 $feDir 下一个 *.spec.ts 都没扫到 —— 判定不可信，不代表前端没有测试问题"
}
$feIt = 0; $feExpect = 0
foreach ($file in $feFiles) {
  $text = [System.IO.File]::ReadAllText($file.FullName)
  $its = ([regex]::Matches($text, $itPat)).Count
  $expects = ([regex]::Matches($text, 'expect\(')).Count
  $feIt += $its; $feExpect += $expects
  $relative = $file.FullName.Substring($repoRoot.Length + 1)
  if ($its -eq 0) { continue }
  if ($expects -eq 0) {
    $fail += "no-assert：$relative 有 $its 个用例但一处 expect 都没有"
  }
  elseif ($expects -lt $its) {
    $warn += ("density：{0} expect 密度 {1:N2}" -f $relative, ($expects / $its))
  }
}
if ($feFiles.Count -gt 0 -and $feIt -eq 0) {
  Add-ScanEmpty "扫到了 $($feFiles.Count) 个 *.spec.ts 但一个用例声明都没有 —— 判定不可信（写法变了？）"
}
if ($feIt -gt 0) {
  $ok += ("frontend-suite：{0} 个 Vitest 用例 / {1} 处 expect（密度 {2:N2}）" -f $feIt, $feExpect, ($feExpect / $feIt))
}

$e2eDir = Join-Path $repoRoot 'frontend\e2e'
# 同上：无条件检查，目录不在也算「扫不到」→ 判不可信，而不是静默跳过。
$e2eFiles = @()
if (Test-Path $e2eDir) {
  $e2eFiles = @(Get-ChildItem $e2eDir -Filter *.spec.ts -File -ErrorAction SilentlyContinue)
}
if ($e2eFiles.Count -eq 0) {
  Add-ScanEmpty "在 $e2eDir 下一个 *.spec.ts 都没扫到 —— 判定不可信"
}
$e2eTests = 0; $e2eExpect = 0
foreach ($file in $e2eFiles) {
  $text = [System.IO.File]::ReadAllText($file.FullName)
  $e2eTests += ([regex]::Matches($text, $e2eItPat)).Count
  $e2eExpect += ([regex]::Matches($text, 'expect\(')).Count
}
if ($e2eFiles.Count -gt 0 -and $e2eTests -eq 0) {
  Add-ScanEmpty "扫到了 $($e2eFiles.Count) 个 e2e spec 但一个 test( 声明都没有 —— 判定不可信"
}
if ($e2eTests -gt 0) {
  $ok += ("e2e-suite：{0} 个 Playwright 用例 / {1} 处 expect（密度 {2:N2}）" -f $e2eTests, $e2eExpect, ($e2eExpect / $e2eTests))
}

# ---------------------------------------------------------------- 输出

Write-Host ''
Write-Host '=== 测试质量机械化检查（06 §8 第 29 条）===' -ForegroundColor Cyan

Write-Host ''
Write-Host '--- 规模基线 ----'
foreach ($line in $ok) { Write-Host ("  [OK]   " + $line) -ForegroundColor Green }

Write-Host ''
Write-Host '--- 可判定问题 ----'
if ($fail.Count -eq 0 -and $warn.Count -eq 0) {
  Write-Host '  [OK]   没有发现可机械判定的问题' -ForegroundColor Green
}
foreach ($line in $fail) { Write-Host ("  [FAIL] " + $line) -ForegroundColor Red }
foreach ($line in $warn) { Write-Host ("  [WARN] " + $line) -ForegroundColor Yellow }

Write-Host ''
if ($fail.Count -gt 0) {
  Write-Host ("结论：未通过（{0} 个 FAIL，{1} 个 WARN）" -f $fail.Count, $warn.Count) -ForegroundColor Red
  exit 1
}
Write-Host ("结论：通过（{0} 个 WARN）" -f $warn.Count) -ForegroundColor Green
exit 0

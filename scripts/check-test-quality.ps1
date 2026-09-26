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
    2) 不允许 @Disabled / @Ignore（跳过的测试 = 隐藏在绿灯后面的缺口）
    3) Thread.sleep 要给出理由（时序脆弱点，必须能在失败时解释自己）
    4) 断言密度（类级 < 1.0 → 警告）
    5) 测试里不得硬编码口令（凭据必须来自常量或配置）
    6) 前端 spec：用例数 vs expect 数（< 1.0 → 警告）
    7) 测试数为正但断言数为 0 的类 → 失败（最强信号）

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

$fail = @()
$warn = @()
$ok = @()

# ---------------------------------------------------------------- 后端

$testFiles = Get-ChildItem -Recurse (Join-Path $repoRoot 'backend\src\test') -Filter *.java |
  Where-Object { $_.FullName -notmatch '[\\/]fixture[\\/]' -and $_.Name -notlike '*fixture*' }

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
  #    拿剥过注释的 `$code` 去查「有没有说明理由」永远查不到——那就变成一条恒真的假检查了。
  if ($sleeps -gt 0 -and $text -notmatch '时序|等待|sleep') {
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
if ((Test-Path $archTestPath) -and (Test-Path $archEffectPath)) {
  $ruleSource = [System.IO.File]::ReadAllText($archTestPath)
  $effectSource = [System.IO.File]::ReadAllText($archEffectPath)
  $rules = [regex]::Matches($ruleSource, '(?m)^\s*static final ArchRule\s+(\w+)') |
    ForEach-Object { $_.Groups[1].Value }
  $uncovered = @()
  foreach ($rule in $rules) {
    if ($effectSource -notmatch [regex]::Escape($rule)) { $uncovered += $rule }
  }
  if ($uncovered.Count -gt 0) {
    $fail += ("arch-rule-uncovered：{0} 条 @ArchTest 规则没有等效性用例（漏配夹具的规则会静默空转）：{1}" -f `
      $uncovered.Count, ($uncovered -join ', '))
  }
  else {
    $ok += ("arch-rule-coverage：{0} 条 @ArchTest 规则全部有等效性用例" -f $rules.Count)
  }
}

# ---------------------------------------------------------------- 前端

$feDir = Join-Path $repoRoot 'frontend\src'
if (Test-Path $feDir) {
  $feFiles = Get-ChildItem -Recurse $feDir -Include *.spec.ts -File
  $feIt = 0; $feExpect = 0
  foreach ($file in $feFiles) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    $its = ([regex]::Matches($text, '(?m)^\s*(it|test)\(')).Count
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
  if ($feIt -gt 0) {
    $ok += ("frontend-suite：{0} 个 Vitest 用例 / {1} 处 expect（密度 {2:N2}）" -f $feIt, $feExpect, ($feExpect / $feIt))
  }
}

$e2eDir = Join-Path $repoRoot 'frontend\e2e'
if (Test-Path $e2eDir) {
  $e2eFiles = Get-ChildItem $e2eDir -Filter *.spec.ts -File
  $e2eTests = 0; $e2eExpect = 0
  foreach ($file in $e2eFiles) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    $e2eTests += ([regex]::Matches($text, '(?m)^\s*test\(')).Count
    $e2eExpect += ([regex]::Matches($text, 'expect\(')).Count
  }
  if ($e2eTests -gt 0) {
    $ok += ("e2e-suite：{0} 个 Playwright 用例 / {1} 处 expect（密度 {2:N2}）" -f $e2eTests, $e2eExpect, ($e2eExpect / $e2eTests))
  }
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

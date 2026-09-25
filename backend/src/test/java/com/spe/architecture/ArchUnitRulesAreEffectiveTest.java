package com.spe.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证 ArchUnit 规则确实会命中违规，而不是「空转绿灯」——09 §6 风险表把这一点列为风险：
 * 包名写错或规则写歪都会让规则永远通过，却给人「已受保护」的错觉。
 *
 * <p>做法：只导入测试期存在的违规夹具（{@code *.fixture} 包），把 {@link ArchitectureTest} 中
 * 真实的规则对象拿来跑，断言它们确实失败。正式的 {@code @AnalyzeClasses} 用 DoNotIncludeTests
 * 排除测试类，因此夹具不会被真实校验看到。
 */
class ArchUnitRulesAreEffectiveTest {

  private static final JavaClasses VIOLATING_FIXTURES =
      new ClassFileImporter()
          .importPackages(
              "com.spe.common.fixture",
              "com.spe.context.supplier.fixture",
              "com.spe.context.alert.fixture",
              "com.spe.context.analytics.fixture");

  @Test
  @DisplayName("R-A 能捕获跨上下文依赖对方 internal")
  void rACatchesCrossContextInternalDependency() {
    assertThatThrownBy(() -> ArchitectureTest.crossContextDependencyOnlyViaApi.check(VIOLATING_FIXTURES))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("R-E 能捕获不在 web 子包里的 Controller")
  void rECatchesMisplacedController() {
    assertThatThrownBy(() -> ArchitectureTest.controllersResideInWebPackage.check(VIOLATING_FIXTURES))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("R-F 能捕获 common 依赖业务上下文")
  void rFCatchesCommonDependingOnContext() {
    assertThatThrownBy(() -> ArchitectureTest.commonDoesNotDependOnContexts.check(VIOLATING_FIXTURES))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("R-G 能捕获上下文之间的循环依赖")
  void rGCatchesContextCycles() {
    assertThatThrownBy(() -> ArchitectureTest.contextsAreFreeOfCycles.check(VIOLATING_FIXTURES))
        .isInstanceOf(AssertionError.class);
  }
}

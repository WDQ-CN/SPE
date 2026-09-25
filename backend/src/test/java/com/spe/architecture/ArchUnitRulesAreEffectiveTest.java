package com.spe.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证 ArchUnit 规则确实会命中违规，而不是「空转绿灯」——09 §6 风险表把这一点列为风险：
 * 包名写错或规则写歪都会让规则永远通过，却给人「已受保护」的错觉。
 *
 * <p>做法：只导入测试期存在的违规夹具（{@code *.fixture} 包），把 {@link ArchitectureTest} 中
 * 真实的规则对象拿来跑，断言它们确实失败。正式的 {@code @AnalyzeClasses} 用 DoNotIncludeTests
 * 排除测试类，因此夹具不会被真实校验看到。
 *
 * <p>七条规则（R-A~R-G）全部纳入验证：R-B/R-C/R-D 的夹具在 P1 补入前只靠 {@code allowEmptyShould}
 * 声明空转，本身没有任何「规则会失败」的证据。
 */
class ArchUnitRulesAreEffectiveTest {

  private static final JavaClasses VIOLATING_FIXTURES =
      new ClassFileImporter()
          .importPackages(
              "com.spe.common.fixture",
              "com.spe.context.supplier.fixture",
              "com.spe.context.alert.fixture",
              "com.spe.context.analytics.fixture",
              "com.spe.context.evaluation.fixture",
              "com.spe.context.evaluation.internal.scoring.fixture");

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

  @Test
  @DisplayName("R-A 能捕获依赖对方上下文的非 api 包（白名单语义，黑名单实现会漏）")
  void rACatchesDependencyOnOtherContextNonApiPackage() {
    JavaClasses fixtures =
        new ClassFileImporter()
            .importPackages("com.spe.context.system.web", "com.spe.context.supplier.fixture");

    EvaluationResult result = ArchitectureTest.crossContextDependencyOnlyViaApi.evaluate(fixtures);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString()).contains("SharedWebHelper");
  }

  @Test
  @DisplayName("R-B 能捕获出现在只读上下文之外的 QueryMapper")
  void rBCatchesQueryMapperOutsideReadOnlyContexts() {
    assertThatThrownBy(
            () -> ArchitectureTest.queryMapperOnlyInReadOnlyContexts.check(VIOLATING_FIXTURES))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("R-B 能捕获带写注解的 QueryMapper")
  void rBCatchesQueryMapperWithWriteAnnotation() {
    assertThatThrownBy(
            () -> ArchitectureTest.queryMapperHasNoWriteAnnotations.check(VIOLATING_FIXTURES))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("R-C 能捕获计分器包依赖 Spring")
  void rCCatchesFrameworkDependencyInScoringPackage() {
    assertThatThrownBy(
            () -> ArchitectureTest.scoreCalculatorStaysFrameworkFree.check(VIOLATING_FIXTURES))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("R-D 能捕获非 final 的事件载荷字段")
  void rDCatchesMutableEventFields() {
    assertThatThrownBy(() -> ArchitectureTest.domainEventFieldsAreFinal.check(VIOLATING_FIXTURES))
        .isInstanceOf(AssertionError.class);
  }
}

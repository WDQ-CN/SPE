package com.spe.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
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
 * <p><b>为什么断言「失败报告里点名了哪个夹具类」而不是「抛了 AssertionError」</b>：
 * 夹具是**一次性全量导入**的，同一条规则只要命中任何一个夹具就会失败。R-A 就有两个违规夹具——
 * {@code SupplierLeaksOtherContextInternal}（依赖对方 internal）与
 * {@code SupplierDependsOnOtherContextWebPackage}（依赖对方 web 包）。若只断言「抛异常」，
 * 规则即使不再拦 internal 依赖也照样绿，而用例名字还写着「能捕获 internal 依赖」。
 * 点名类名之后，每个用例只为自己那一条违规负责（这一处加强由测试体系审查发现）。
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
              "com.spe.context.evaluation.internal.scoring.calculator.fixture",
              // R-I 的夹具必须在 ..internal.approval.. 包下才会被那条规则的选取器命中
              "com.spe.context.evaluation.internal.approval.fixture",
              // R-H 的两条规则针对 Controller 命名，夹具单独放在这里（不属任何上下文，
              // 因此不会被 R-A/R-E/R-F/R-G 命中，一个夹具只违反自己那一条）
              "com.spe.architecture.fixture");

  /** 断言规则在夹具集上报出违规，且失败报告点名了预期的夹具类。 */
  private static void assertCatches(ArchRule rule, String expectedFixture) {
    EvaluationResult result = rule.evaluate(VIOLATING_FIXTURES);

    assertThat(result.hasViolation()).as("规则应当报出违规").isTrue();
    assertThat(result.getFailureReport().toString())
        .as("失败报告应点名 %s，否则可能是被同批夹具里的其它违规带红的", expectedFixture)
        .contains(expectedFixture);
  }

  @Test
  @DisplayName("R-A 能捕获跨上下文依赖对方 internal")
  void rACatchesCrossContextInternalDependency() {
    assertCatches(
        ArchitectureTest.crossContextDependencyOnlyViaApi, "SupplierLeaksOtherContextInternal");
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
    assertCatches(ArchitectureTest.queryMapperOnlyInReadOnlyContexts, "LeakyQueryMapper");
  }

  @Test
  @DisplayName("R-B 能捕获带写注解的 QueryMapper")
  void rBCatchesQueryMapperWithWriteAnnotation() {
    assertCatches(ArchitectureTest.queryMapperHasNoWriteAnnotations, "LeakyQueryMapper");
  }

  @Test
  @DisplayName("R-C 能捕获计分器包依赖 Spring")
  void rCCatchesFrameworkDependencyInScoringPackage() {
    assertCatches(ArchitectureTest.scoreCalculatorStaysFrameworkFree, "FrameworkDependentCalculator");
  }

  @Test
  @DisplayName("R-D 能捕获非 final 的事件载荷字段")
  void rDCatchesMutableEventFields() {
    assertCatches(ArchitectureTest.domainEventFieldsAreFinal, "MutableEvent");
  }

  @Test
  @DisplayName("R-E 能捕获不在 web 子包里的 Controller")
  void rECatchesMisplacedController() {
    assertCatches(ArchitectureTest.controllersResideInWebPackage, "ControllerOutsideWebPackage");
  }

  @Test
  @DisplayName("R-E 能捕获依赖其他上下文的 Controller（不含 internal 的整上下文禁止）")
  void rECatchesControllerDependingOnOtherContext() {
    // 单独导入这个夹具：它是 @RestController 且依赖对方 internal，会同时被 R-A 命中，
    // 混进共享夹具集会让别的用例的失败报告不干净
    JavaClasses fixtures =
        new ClassFileImporter().importPackages("com.spe.context.supplier.web.fixture");

    EvaluationResult result = ArchitectureTest.controllersStayInOwnContext.evaluate(fixtures);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("SupplierWebControllerWithForeignContextDependency");
  }

  @Test
  @DisplayName("R-F 能捕获 common 依赖业务上下文")
  void rFCatchesCommonDependingOnContext() {
    assertCatches(ArchitectureTest.commonDoesNotDependOnContexts, "CommonDependingOnContext");
  }

  @Test
  @DisplayName("R-G 能捕获上下文之间的循环依赖")
  void rGCatchesContextCycles() {
    assertCatches(ArchitectureTest.contextsAreFreeOfCycles, "CycleStarter");
  }

  @Test
  @DisplayName("R-H 能捕获漏写类级 @PreAuthorize 的 Controller（默认拒绝）")
  void rHCatchesControllerWithoutClassLevelAuthorization() {
    assertCatches(
        ArchitectureTest.controllersDeclareClassLevelAuthorization, "AuthorizationMissingController");
  }

  @Test
  @DisplayName("R-H 按注解选取：类名不以 Controller 结尾的 @RestController 也照样抓（P2-12）")
  void rHCatchesAnnotatedControllerThatIsNotNamedController() {
    assertCatches(
        ArchitectureTest.controllersDeclareClassLevelAuthorization, "TaskWebEndpoint");
  }

  @Test
  @DisplayName("R-H 能捕获用了 @RequestBody 但没带 @Valid 的参数")
  void rHCatchesUnvalidatedRequestBody() {
    assertCatches(
        ArchitectureTest.requestBodyParametersAreValidated, "UnvalidatedRequestBodyController");
  }

  @Test
  @DisplayName("R-I 能捕获只增不改表的 Mapper 继承 BaseMapper（03 §38 的 P2-3）")
  void rICatchesAppendOnlyMapperInheritingBaseMapper() {
    assertCatches(
        ArchitectureTest.appendOnlyMappersDoNotInheritBaseMapper,
        "AppendOnlyMapperExtendsBaseMapper");
  }
}

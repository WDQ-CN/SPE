package com.spe.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.spe.common.event.DomainEvent;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * 耦合度的自动验证（05 §12 R-A~R-G）：规则即规格，随 {@code mvn test} 每次运行。
 *
 * <p><b>关于空转</b>：ArchUnit 1.x 默认在规则的 {@code that()} 匹配不到任何类时判为失败。
 * P0 尚无 QueryMapper / ScoreCalculator / 领域事件的实现类，这几条规则按 09 WP1「空包阶段允许空转通过」
 * 显式声明 {@code allowEmptyShould(true)}——它们是<b>按设计</b>空转，对应代码在后面阶段到达时规则自动生效，
 * 属「新代码到达即纳入」事项，本类无需修改。
 *
 * <p>R-E 的「Controller 签名与方法体不出现实体类」需等实体命名约定在 P1 定稿后再补规则；
 * 当前实现其可判定部分：Controller 归属 web 子包、且不跨上下文。
 */
@AnalyzeClasses(packages = "com.spe", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String CONTEXT_PREFIX = "com.spe.context.";

  /** R-A 契约隔离：跨上下文只允许依赖对方 api 包，internal/query 永不外泄（05 §4.3 / ADR-10）。 */
  @ArchTest
  static final ArchRule crossContextDependencyOnlyViaApi =
      classes().that().resideInAPackage("..context..")
          .should(dependOnOtherContextNonApi(false))
          .because("跨上下文只允许依赖对方 api 包（05 §4.3）");

  /** R-B 写收敛读放开：QueryMapper 只允许存在于 analytics/alert（05 §12）。 */
  @ArchTest
  static final ArchRule queryMapperOnlyInReadOnlyContexts =
      classes().that().haveSimpleNameEndingWith("QueryMapper")
          .should().resideInAnyPackage("..context.analytics..", "..context.alert..")
          .because("只读查询器只属于纯只读/读侧上下文（05 §5.1）")
          .allowEmptyShould(true);

  /** R-B（续）QueryMapper 只含 SELECT，不得出现写入注解（05 §8.3）。 */
  @ArchTest
  static final ArchRule queryMapperHasNoWriteAnnotations =
      noMethods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("QueryMapper")
          .should().beAnnotatedWith(Insert.class)
          .orShould().beAnnotatedWith(Update.class)
          .orShould().beAnnotatedWith(Delete.class)
          .because("QueryMapper 仅含 SELECT（05 §8.3）")
          .allowEmptyShould(true);

  /** R-C 计分器纯函数：ScoreCalculator 所在包不得依赖 Spring / MyBatis（05 ADR-4）。 */
  @ArchTest
  static final ArchRule scoreCalculatorStaysFrameworkFree =
      noClasses().that().resideInAPackage("..context.evaluation.internal.scoring..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "org.apache.ibatis..", "com.baomidou..")
          .because("计分器是不碰 Spring/MyBatis 的纯函数（ADR-4）")
          .allowEmptyShould(true);

  /** R-D 事件卫生：领域事件字段全部 final（record 实现天然满足）；监听器不回调发布方 internal 由 R-A 保证。 */
  @ArchTest
  static final ArchRule domainEventFieldsAreFinal =
      fields().that().areDeclaredInClassesThat().areAssignableTo(DomainEvent.class)
          .should().beFinal()
          .because("事件载荷不可变（05 §5.3）")
          .allowEmptyShould(true);

  /** R-E 分层：Controller 归属所在上下文的 web 子包（05 §4.3）。 */
  @ArchTest
  static final ArchRule controllersResideInWebPackage =
      classes().that().areAnnotatedWith(RestController.class)
          .or().areAnnotatedWith(Controller.class)
          .should().resideInAPackage("..web..")
          .because("Controller 属于所在上下文的 web 子包（05 §4.3）");

  /** R-E（续）Controller 只依赖自身上下文与 common，不跨上下文（05 §12 R-E）。 */
  @ArchTest
  static final ArchRule controllersStayInOwnContext =
      classes().that().areAnnotatedWith(RestController.class)
          .should(dependOnOtherContextNonApi(true))
          .because("Controller 仅依赖自身上下文 service/api（05 §12 R-E）");

  /** R-F 共享内核最小：common 不依赖任何业务上下文（05 §12）。 */
  @ArchTest
  static final ArchRule commonDoesNotDependOnContexts =
      noClasses().that().resideInAPackage("com.spe.common..")
          .should().dependOnClassesThat().resideInAPackage("com.spe.context..")
          .because("共享内核不依赖业务上下文（05 §12 R-F）");

  /** R-G 依赖无环：上下文之间不得出现循环依赖（05 §5.1）。 */
  @ArchTest
  static final ArchRule contextsAreFreeOfCycles =
      slices().matching("com.spe.context.(*)..").should().beFreeOfCycles()
          .because("依赖方向静态单向、无环（05 §5.1）");

  /** 取包名所属的上下文名；不在 context 包下返回 null。 */
  private static String contextOf(String packageName) {
    if (!packageName.startsWith(CONTEXT_PREFIX)) {
      return null;
    }
    String rest = packageName.substring(CONTEXT_PREFIX.length());
    int separator = rest.indexOf('.');
    return separator < 0 ? rest : rest.substring(0, separator);
  }

  /**
   * @param wholeContext true 表示禁止依赖其他上下文的任何包（Controller 用）；
   *                     false 表示仅禁止依赖其他上下文的 internal/query（跨上下文普通类用）
   */
  private static ArchCondition<JavaClass> dependOnOtherContextNonApi(boolean wholeContext) {
    String description =
        wholeContext ? "只依赖自身上下文与 common，不跨上下文" : "只允许依赖其他上下文的 api 包";
    return new ArchCondition<>(description) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        String ownContext = contextOf(item.getPackageName());
        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          String targetContext = contextOf(target.getPackageName());
          if (targetContext == null || targetContext.equals(ownContext)) {
            continue;
          }
          String targetPackage = target.getPackageName();
          boolean violation =
              wholeContext || targetPackage.contains(".internal") || targetPackage.contains(".query");
          if (violation) {
            events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
          }
        }
      }
    };
  }
}

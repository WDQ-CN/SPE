package com.spe.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.spe.common.event.DomainEvent;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.validation.Valid;
import java.util.Set;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
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

  /**
   * R-H 默认拒绝：所有 Controller 必须有**类级** `@PreAuthorize`（免鉴权端点显式列入白名单）。
   *
   * <p>为什么需要机械化保证：安全链只要求「已认证」，细粒度权限全靠方法上的 `@PreAuthorize`。
   * 新增一个 Controller 时忘了写它**不会有任何报错**——只是任何已登录用户都能访问：越权静默发生，
   * 前端隐藏菜单与路由守卫都拦不住直接调接口。把「默认拒绝」变成规则，漏写即测试变红（03 §18）。
   */
  @ArchTest
  static final ArchRule controllersDeclareClassLevelAuthorization =
      classes().that().haveSimpleNameEndingWith("Controller")
          .and(notPublicEndpoint())
          .should().beAnnotatedWith(PreAuthorize.class)
          .because("端点默认拒绝：漏一个 @PreAuthorize 就是一处越权（06 §8 第 10 条）");

  /** 免鉴权端点白名单：登录接口本身必须放行（否则永远登不进来），ping 是健康探针。 */
  private static final Set<String> PUBLIC_CONTROLLERS = Set.of("AuthController", "PingController");

  private static DescribedPredicate<JavaClass> notPublicEndpoint() {
    return new DescribedPredicate<>("不是免鉴权的公开端点") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !PUBLIC_CONTROLLERS.contains(javaClass.getSimpleName());
      }
    };
  }

  /**
   * R-H（续）入参校验不靠自觉：Controller 的 `@RequestBody` 参数必须带 `@Valid`。
   *
   * <p>漏了它不会报错，只是该端点的 JSR-303 注解全部失效——超长字符串会一路走到 INSERT，
   * 最后漏成 5xxx（06 §2.6 的红线：入口不拦，错误码就跑偏）。
   */
  @ArchTest
  static final ArchRule requestBodyParametersAreValidated =
      methods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller")
          .and().arePublic()
          .should(validateRequestBodyParameters())
          .because("外部输入必须过校验（06 §8 第 11 条）");

  private static ArchCondition<JavaMethod> validateRequestBodyParameters() {
    return new ArchCondition<>("对所有 @RequestBody 参数使用 @Valid") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        for (JavaParameter parameter : method.getParameters()) {
          if (parameter.isAnnotatedWith(RequestBody.class) && !parameter.isAnnotatedWith(Valid.class)) {
            events.add(
                SimpleConditionEvent.violated(
                    method, method.getFullName() + " 的 @RequestBody 参数缺少 @Valid"));
          }
        }
      }
    };
  }

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
   *                     false 表示仅允许依赖其他上下文的 api 包（跨上下文普通类用）
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
          // 白名单判定：目标必须落在对方的 api 包里（05 §12 R-A 的原文是「跨上下文仅允许依赖 ..api..」）。
          // 只拦 internal/query 的黑名单写法会漏掉对方其它包，例如误放进 ..web.. 的类。
          // 用「等于 api 包或以 api. 开头」而不是 contains(".api")：后者是过宽的字符串包含，
          // `...context.system.internal.apix` 之类也会被判成合法 api 包（测试体系审查发现）。
          String targetApiPackage = CONTEXT_PREFIX + targetContext + ".api";
          boolean targetIsApi =
              target.getPackageName().equals(targetApiPackage)
                  || target.getPackageName().startsWith(targetApiPackage + ".");
          if (wholeContext || !targetIsApi) {
            events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
          }
        }
      }
    };
  }
}

package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.spe.context.system.api.GradeThresholds;
import com.spe.context.system.internal.setting.SettingService;
import com.spe.context.system.internal.setting.SysSetting;
import com.spe.context.system.internal.setting.SysSettingMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 等级阈值的缓存行为（03 §16 的 PERF-3）：读一次之后复用缓存，改动之后必须失效——
 * 否则「管理员把 A 档从 90 调到 91，算分仍按 90」这种错误会非常隐蔽。
 *
 * <p>用 `@MockitoBean` 替换 mapper 来**数查库次数**：这是行为级证据（比断言缓存内部结构稳），
 * 「第二次读没有查库」直接说明缓存生效。替换 Bean 会让 Spring 新建一个上下文缓存条目，
 * 共享容器由 `TestcontainersConfiguration` 的静态单例保证只起一次（04-029）。
 *
 * <p>阈值字段是 `BigDecimal`（06 §2：全程 BigDecimal，不得出现 double），断言用 `isEqualByComparingTo`
 * 而非 `isEqualTo`——后者对 `BigDecimal` 是「值 + 标度」都相等才算过。
 */
class ThresholdCacheIntegrationTest extends AbstractIntegrationTest {

  private static final String KEY = "grade.thresholds";
  private static final String CACHE_NAME = "gradeThresholds";

  @MockitoBean private SysSettingMapper sysSettingMapper;

  @Autowired private SettingService settingService;
  @Autowired private CacheManager cacheManager;
  @Autowired private MeterRegistry meterRegistry;

  /**
   * `/actuator/metrics` 上的「因未命中而查库」次数（03 §19）。它和上面 `verify(mapper)` 的 mock 计数
   * 是同一件事的两个视角：一个是被测行为，一个是外部可观测口径——两者一起断言，才说明"接上指标"没接歪。
   */
  private double loads() {
    return meterRegistry.get("spe.settings.thresholds.loads").counter().count();
  }

  /** 缓存是进程内单例、**不随测试事务回滚**，每个用例从干净缓存开始，否则计数断言会互相干扰。 */
  @BeforeEach
  void clearCache() {
    Cache cache = cacheManager.getCache(CACHE_NAME);
    if (cache != null) {
      cache.clear();
    }
  }

  private static SysSetting setting(String json) {
    SysSetting setting = new SysSetting();
    setting.setSettingKey(KEY);
    setting.setSettingValue(json);
    return setting;
  }

  private static GradeThresholds thresholds(String a, String b, String c, String d) {
    return new GradeThresholds(
        new BigDecimal(a), new BigDecimal(b), new BigDecimal(c), new BigDecimal(d));
  }

  @Test
  @DisplayName("读阈值进缓存：连续两次读只查一次库（P4 定稿算分的热路径）")
  void secondReadIsServedFromCache() {
    when(sysSettingMapper.findByKey(KEY)).thenReturn(Optional.empty());

    double before = loads();

    assertThat(settingService.gradeThresholds().a()).isEqualByComparingTo("90");
    assertThat(settingService.gradeThresholds().a()).isEqualByComparingTo("90");

    verify(sysSettingMapper, times(1)).findByKey(KEY);
    // 用**增量**而不是绝对值：Counter 是进程内单例，会跨用例累积
    assertThat(loads() - before).as("两次读只查一次库 → 未命中计数只 +1").isEqualTo(1.0);
  }

  @Test
  @DisplayName("更新阈值后缓存失效：下一次读回到库里取新值")
  void updateInvalidatesCache() {
    when(sysSettingMapper.findByKey(KEY))
        // 1) 用例内的首次读：库里没有 → 返回代码默认值 90，并写入缓存
        .thenReturn(Optional.empty())
        // 2) updateGradeThresholds 内部的 before 读（自调用不走缓存代理）→ 仍是默认值
        .thenReturn(Optional.empty())
        // 3) 更新之后的那次读：必须重新查库并拿到新值
        .thenReturn(Optional.of(setting("{\"a\":91,\"b\":81,\"c\":71,\"d\":0}")));

    double before = loads();

    assertThat(settingService.gradeThresholds().a()).isEqualByComparingTo("90");

    settingService.updateGradeThresholds(thresholds("91", "81", "71", "0"));

    assertThat(settingService.gradeThresholds().a())
        .as("更新后必须重新查库，否则会一直用旧阈值算分")
        .isEqualByComparingTo("91");
    assertThat(loads() - before)
        .as(
            "一共查库 3 次：首次读 + updateGradeThresholds 内部的 before 读（自调用不走缓存代理，"
                + "所以它也是一次真实查库）+ 缓存被清后的再读")
        .isEqualTo(3.0);
  }
}

package com.spe.context.system.internal.setting;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存开关（03 §16 的 PERF-3）与它的指标（03 §19）。
 *
 * <p>本项目只在「读多写少、写入口集中在本进程内」的设置类数据上开缓存，目前唯一使用者是等级阈值
 * （{@link SettingService#gradeThresholds()}）——P4 定稿算分时每个评估单都要读阈值，属热路径。
 *
 * <p>用 Spring 默认的 `ConcurrentMapCacheManager`（进程内、零新增依赖）。它**没有 TTL**，失效完全依赖
 * 写路径上的 `@CacheEvict`，因此约束是：**只缓存写入口在本进程内的数据**。外部直接改库会让缓存读到旧值；
 * 多实例部署时各进程各持一份。两条都已登记在 11 §9（演示项目可接受），将来需要时换 Redis/Caffeine。
 *
 * <p><b>为什么没有 hit 计数</b>：命中发生在 `@Cacheable` 的代理层，方法体根本不会执行，所以业务代码
 * 看不到命中。要精确拿到 hit/miss，得把手写缓存替换掉声明式缓存——为两个计数改掉缓存形态，代价与收益
 * 不匹配。这里改为暴露**能回答「缓存有没有在工作」的两个量**：未命中查库次数（loads）与当前条目数（size）。
 * Spring Boot 自动注册的 `cache.gets` 若可用，则作为补充（动态创建的 cache 可能绑不上，见 03 §19）。
 */
@Configuration
@EnableCaching
public class SettingCacheConfig {

  /**
   * 阈值的「因未命中而查库」次数：由 {@link SettingService#gradeThresholds()} 在**方法体内**累加，
   * 而 `@Cacheable` 命中时不执行方法体——所以这个计数语义上正好等于 miss 次数。
   */
  @Bean
  Counter thresholdsLoadsCounter(MeterRegistry meterRegistry) {
    return Counter.builder("spe.settings.thresholds.loads")
        .description("等级阈值因缓存未命中而查库的次数（与缓存命中互补）")
        .register(meterRegistry);
  }

  /** 阈值缓存当前条目数：0 表示还没装载过，长期 >1 说明 key 设计有问题（这个缓存只该有一条）。 */
  @Bean
  Gauge thresholdsCacheSizeGauge(CacheManager cacheManager, MeterRegistry meterRegistry) {
    return Gauge.builder(
            "spe.settings.thresholds.cache.size", cacheManager, SettingCacheConfig::thresholdsCacheSize)
        .description("等级阈值缓存的条目数")
        .register(meterRegistry);
  }

  private static double thresholdsCacheSize(CacheManager cacheManager) {
    Cache cache = cacheManager.getCache(SettingService.GRADE_THRESHOLDS_CACHE);
    if (cache == null) {
      return 0;
    }
    Object nativeCache = cache.getNativeCache();
    // ConcurrentMapCache 的底层就是 ConcurrentMap；换成其它实现时宁可不报数（NaN）也不要猜
    return nativeCache instanceof Map<?, ?> entries ? entries.size() : Double.NaN;
  }
}

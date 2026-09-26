package com.spe.context.system.internal.setting;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存开关（03 §16 的 PERF-3）。
 *
 * <p>本项目只在「读多写少、写入口集中在本进程内」的设置类数据上开缓存，目前唯一使用者是等级阈值
 * （{@link SettingService#gradeThresholds()}）——P4 定稿算分时每个评估单都要读阈值，属热路径。
 *
 * <p>用 Spring 默认的 `ConcurrentMapCacheManager`（进程内、零新增依赖）。它**没有 TTL**，失效完全依赖
 * 写路径上的 `@CacheEvict`，因此约束是：**只缓存写入口在本进程内的数据**。外部直接改库会让缓存读到旧值；
 * 多实例部署时各进程各持一份。两条都已登记在 11 §9（演示项目可接受），将来需要时换 Redis/Caffeine。
 */
@Configuration
@EnableCaching
public class SettingCacheConfig {}

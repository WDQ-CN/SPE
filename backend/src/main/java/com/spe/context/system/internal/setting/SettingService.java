package com.spe.context.system.internal.setting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.api.GradeThresholds;
import io.micrometer.core.instrument.Counter;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统设置服务（01 SM2 / §6.3）。
 *
 * <p><b>默认值归属</b>（10 §E9）：等级阈值的出厂默认由代码持有（{@link GradeThresholds#defaults()}），
 * 设置键缺失时**返回默认值但不落库**，只有 ADMIN 首次保存才写 `sys_setting`——否则默认值会同时存在于
 * 迁移脚本与代码两处，早晚漂移。
 *
 * <p><b>校验</b>（01 §6.3）：`0 ≤ D ≤ C ≤ B ≤ A ≤ 100`，**允许相等**（等于停用该档）；本类是唯一
 * 校验点，前端联动校验只是体验（06 §3.2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingService {

  /** 设置键：阈值 JSON（`V2__sys_setting.sql` 的列注释即以此为例）。 */
  static final String GRADE_THRESHOLDS_KEY = "grade.thresholds";

  /** 缓存名（03 §16 的 PERF-3）：写入口只有 {@link #updateGradeThresholds}，故用精确 evict 失效。 */
  static final String GRADE_THRESHOLDS_CACHE = "gradeThresholds";

  private final SysSettingMapper sysSettingMapper;
  private final ObjectMapper objectMapper;
  private final AuditFacade auditFacade;
  /** 缓存未命中而查库的次数（03 §19）；在 {@link SettingCacheConfig} 里注册。 */
  private final Counter thresholdsLoads;

  /**
   * 读阈值：缺失或存量值已损坏时回落到代码默认值，保证调用方总能拿到可用阈值。
   *
   * <p>**结果进缓存**（03 §16 的 PERF-3）：P4 定稿算分时每个评估单都要读它，属热路径；
   * 缓存由写路径上的 `@CacheEvict` 失效，因此不存在「改了不生效」的窗口。
   */
  @Cacheable(cacheNames = GRADE_THRESHOLDS_CACHE)
  @Transactional(readOnly = true)
  public GradeThresholds gradeThresholds() {
    // 计数点必须在方法体内：`@Cacheable` 命中时**不会执行方法体**，所以这一句正好只在真正查库时执行——
    // 它就是「缓存未命中次数」，与命中次数互补（命中在代理层，业务代码看不到，理由见 SettingCacheConfig）。
    thresholdsLoads.increment();
    return sysSettingMapper
        .findByKey(GRADE_THRESHOLDS_KEY)
        .map(SysSetting::getSettingValue)
        .map(this::readThresholds)
        .orElseGet(GradeThresholds::defaults);
  }

  /**
   * 保存阈值（01 SM2）：校验 → upsert → 审计，全部在同一事务内（05 §5.5 T6）。
   *
   * <p>成功后清缓存（`@CacheEvict` 默认在方法成功返回后执行——失败时不清，避免把旧值又读回来）。
   * 方法内的 {@code gradeThresholds()} 调用是**自调用**、不走缓存代理，因此 `before` 拿到的必是库中旧值。
   */
  @CacheEvict(cacheNames = GRADE_THRESHOLDS_CACHE, allEntries = true)
  @Transactional
  public GradeThresholds updateGradeThresholds(GradeThresholds thresholds) {
    if (!thresholds.satisfiesOrdering()) {
      throw new BizException(ErrorCode.THRESHOLD_INVALID);
    }
    GradeThresholds before = gradeThresholds();
    sysSettingMapper.upsert(GRADE_THRESHOLDS_KEY, writeThresholds(thresholds));

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("key", GRADE_THRESHOLDS_KEY);
    detail.put("before", before);
    detail.put("after", thresholds);
    auditFacade.record(AuditAction.SETTING_UPDATE, AuditTargetType.SETTING, null, detail);
    log.info("更新等级阈值：{}", thresholds);
    return thresholds;
  }

  /** 存量值损坏（被手工改坏）时不抛异常而是回落默认值并告警：设置读不出去不该让整个系统起不来。 */
  private GradeThresholds readThresholds(String json) {
    try {
      GradeThresholds thresholds = objectMapper.readValue(json, GradeThresholds.class);
      return thresholds.satisfiesOrdering() ? thresholds : GradeThresholds.defaults();
    } catch (JsonProcessingException ex) {
      log.warn("等级阈值存量值无法解析，回落默认值：{}", ex.getMessage());
      return GradeThresholds.defaults();
    }
  }

  private String writeThresholds(GradeThresholds thresholds) {
    try {
      return objectMapper.writeValueAsString(thresholds);
    } catch (JsonProcessingException ex) {
      // 序列化失败属代码缺陷（record 结构固定），不是可恢复的业务异常
      throw new IllegalStateException("等级阈值序列化失败", ex);
    }
  }
}

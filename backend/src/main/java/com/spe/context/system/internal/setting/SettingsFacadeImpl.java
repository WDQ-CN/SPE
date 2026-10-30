package com.spe.context.system.internal.setting;

import com.spe.context.system.api.AlertRules;
import com.spe.context.system.api.GradeThresholds;
import com.spe.context.system.api.SettingsFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link SettingsFacade} 的实现（05 §8.3）：跨上下文读取设置项，实体不出上下文。 */
@Component
@RequiredArgsConstructor
public class SettingsFacadeImpl implements SettingsFacade {

  private final SettingService settingService;

  @Override
  @Transactional(readOnly = true)
  public GradeThresholds gradeThresholds() {
    return settingService.gradeThresholds();
  }

  @Override
  @Transactional(readOnly = true)
  public AlertRules alertRules() {
    return settingService.alertRules();
  }
}

package com.spe.context.system.internal.web;

import com.spe.common.web.Result;
import com.spe.context.system.api.AlertRules;
import com.spe.context.system.internal.setting.SettingService;
import com.spe.context.system.internal.web.dto.AlertRulesRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预警规则设置（01 SM3；M5 W2，`22 §2 D9`）。
 *
 * <p>与等级阈值同一条「按键分路径」的口径：每个设置项有强类型出入参，不让 JSON 漂在通用 Map 里。
 * 权限锚定 01 §7：系统设置**仅 ADMIN**；键缺失时返回代码默认值而不落库（10 §E9）。
 */
@RestController
@RequestMapping("/api/settings/alert-rules")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AlertRuleController {

  private final SettingService settingService;

  @GetMapping
  public Result<AlertRules> get() {
    return Result.ok(settingService.alertRules());
  }

  /** 缺字段 → 2001；数值越界 → 2034（`ALERT_RULE_INVALID`，由服务层统一判定）。 */
  @PutMapping
  public Result<AlertRules> update(@Valid @RequestBody AlertRulesRequest request) {
    return Result.ok(settingService.updateAlertRules(request.toRecord()));
  }
}

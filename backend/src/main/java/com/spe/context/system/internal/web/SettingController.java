package com.spe.context.system.internal.web;

import com.spe.common.web.Result;
import com.spe.context.system.api.GradeThresholds;
import com.spe.context.system.internal.setting.SettingService;
import com.spe.context.system.internal.web.dto.GradeThresholdsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置接口（02 §4 的 `GET/PUT /api/settings/grade-thresholds`，10 §E14 定的形状）。
 *
 * <p>鉴权锚定 01 §7：系统设置**仅 ADMIN**。读取时若设置键缺失，返回代码持有的默认阈值而不落库
 * （10 §E9）——首次保存才写 `sys_setting`。
 *
 * <p>按键分路径而不是泛化 `GET/PUT /settings`：后续预警规则、默认审批链各自成路径，
 * 每个设置项都有自己的强类型出入参，不让 JSON 结构漂在一个通用的 Map 里。
 */
@RestController
@RequestMapping("/api/settings/grade-thresholds")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class SettingController {

  private final SettingService settingService;

  @GetMapping
  public Result<GradeThresholds> get() {
    return Result.ok(settingService.gradeThresholds());
  }

  /** 01 §6.3：`0 ≤ D ≤ C ≤ B ≤ A ≤ 100`，允许相等；不满足返回 `THRESHOLD_INVALID(2005)`。 */
  @PutMapping
  public Result<GradeThresholds> update(@Valid @RequestBody GradeThresholdsRequest request) {
    GradeThresholds thresholds =
        new GradeThresholds(request.a(), request.b(), request.c(), request.d());
    return Result.ok(settingService.updateGradeThresholds(thresholds));
  }
}

package com.spe.context.system.internal.web.dto;

import com.spe.context.system.api.AlertRules;
import jakarta.validation.constraints.NotNull;

/**
 * 预警规则的保存入参（M5 SM3；形状与 `AlertRules` 一致）。
 *
 * <p>数值字段用包装类型 + `@NotNull`：原始 `int` 在 JSON 缺字段时会被 Jackson 填 0，
 * 而 0 对阈值意味着「关闭该规则」、对降幅意味着「任意下降都触发」——静默改语义比报错更危险。
 * 范围校验交给服务层 `AlertRules.satisfies()`，统一返回 **2034**（`ALERT_RULE_INVALID`）。
 */
public record AlertRulesRequest(
    @NotNull(message = "预警规则必须指明是否启用") Boolean enabled,
    @NotNull(message = "总分阈值不能为空") Integer totalThreshold,
    @NotNull(message = "关键维度阈值不能为空") Integer keyDimensionThreshold,
    @NotNull(message = "连续期数不能为空") Integer trendPeriods,
    @NotNull(message = "累计降幅不能为空") Integer trendDrop) {

  public AlertRules toRecord() {
    return new AlertRules(enabled, totalThreshold, keyDimensionThreshold, trendPeriods, trendDrop);
  }
}

package com.spe.context.evaluation.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 暂存打分（01 E2 / 13 §E18 的 `PUT /api/sheets/{id}/scores`）：**只传本次改动的格子**。
 *
 * <p>`rawValue` 的 `@Digits` 是**第一层**（快速失败、错误信息好看）；真正的边界判定在
 * `ScoreWriter`→`ScoreStrategies`（同一套规则，Excel 导入也走它，04-072 的教训）。
 */
public record SaveScoresRequest(
    @NotNull(message = "缺少评分内容") @Size(max = 200, message = "一次最多提交 200 个指标")
        List<@Valid Entry> scores) {

  /** 一格。 */
  public record Entry(
      @Positive(message = "指标不合法") long indicatorId,
      @NotNull(message = "录入值不能为空")
          @Digits(integer = 8, fraction = 2, message = "录入值最多两位小数")
          BigDecimal rawValue) {}
}

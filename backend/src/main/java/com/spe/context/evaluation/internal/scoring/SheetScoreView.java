package com.spe.context.evaluation.internal.scoring;

import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import java.math.BigDecimal;
import java.util.List;

/**
 * 打分页需要的"一张评分单 + 它的指标清单与已填值"（01 E2 / 13 §E18）。
 *
 * <p>指标带上了 `standard`（档位文案 / 人工换算表）与 `unit`：**这不是装饰**——04-087 的教训是
 * "DIRECT 反向指标的录入值是人工换算后的分"，界面上不给出换算表，用户填 0 就会被静默算成 0 分。
 */
public record SheetScoreView(
    long sheetId,
    long taskId,
    long taskSupplierId,
    long dimensionId,
    String dimensionName,
    String status,
    /**
     * 评分单的乐观锁版本：**打分页必须拿到它**——BTPOST /sheets/{id}/submitBT 与改派都要求带版本
     * （02 §3 的守卫 UPDATE 口径）。W6 对接时发现视图里漏了它，补在这里（14 §W6.1 记了这次缺口）。
     */
    int version,
    Long assigneeId,
    List<IndicatorEntry> indicators) {

  /**
   * 一个指标在打分页上的全部信息。
   *
   * @param rawValue 已保存的原始值；null = 还没填
   * @param scoreValue 已保存的换算分；null = 还没填
   */
  public record IndicatorEntry(
      long indicatorId,
      String code,
      String name,
      String unit,
      IndicatorType type,
      ScoreMode scoreMode,
      BigDecimal weight,
      BigDecimal targetValue,
      String standard,
      BigDecimal rawValue,
      BigDecimal scoreValue) {}
}

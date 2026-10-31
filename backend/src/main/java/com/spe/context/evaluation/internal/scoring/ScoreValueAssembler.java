package com.spe.context.evaluation.internal.scoring;

import com.spe.context.evaluation.internal.sheet.Sheet;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「评分单 + 评分记录」组装成 {@code ScoreCalculator} 的输入（维度 id → 指标原始值）。
 *
 * <p>定稿（T3）与报告试算都走这一份映射：两处各写一遍时，只要有一处漏了"sheetId → dimensionId"
 * 或把 null 当 0，报告与定稿就会给出不同的分数（CLAUDE 第 3 条：同一事实只在一处定义）。
 */
public final class ScoreValueAssembler {

  private ScoreValueAssembler() {}

  public static Map<Long, Map<Long, BigDecimal>> byDimension(
      List<Sheet> sheets, List<Score> scores) {
    Map<Long, Long> dimensionBySheet = new HashMap<>();
    Map<Long, Map<Long, BigDecimal>> values = new HashMap<>();
    for (Sheet sheet : sheets) {
      dimensionBySheet.put(sheet.getId(), sheet.getDimensionId());
      values.putIfAbsent(sheet.getDimensionId(), new LinkedHashMap<>());
    }
    for (Score score : scores) {
      Long dimensionId = dimensionBySheet.get(score.getSheetId());
      if (dimensionId == null || score.getRawValue() == null) {
        continue;
      }
      values.get(dimensionId).put(score.getIndicatorId(), score.getRawValue());
    }
    return values;
  }

  /** 看板口径：按 taskSupplier → dimension → 指标原始值分组（一次取回全任务评分记录）。 */
  public static Map<Long, Map<Long, Map<Long, BigDecimal>>> byTaskSupplier(
      List<Sheet> sheets, List<Score> scores) {
    Map<Long, Long> taskSupplierBySheet = new HashMap<>();
    Map<Long, Long> dimensionBySheet = new HashMap<>();
    Map<Long, Map<Long, Map<Long, BigDecimal>>> values = new HashMap<>();
    for (Sheet sheet : sheets) {
      taskSupplierBySheet.put(sheet.getId(), sheet.getTaskSupplierId());
      dimensionBySheet.put(sheet.getId(), sheet.getDimensionId());
      values
          .computeIfAbsent(sheet.getTaskSupplierId(), key -> new HashMap<>())
          .putIfAbsent(sheet.getDimensionId(), new LinkedHashMap<>());
    }
    for (Score score : scores) {
      Long taskSupplierId = taskSupplierBySheet.get(score.getSheetId());
      Long dimensionId = dimensionBySheet.get(score.getSheetId());
      if (taskSupplierId == null || dimensionId == null || score.getRawValue() == null) {
        continue;
      }
      values
          .get(taskSupplierId)
          .get(dimensionId)
          .put(score.getIndicatorId(), score.getRawValue());
    }
    return values;
  }
}

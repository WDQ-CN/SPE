package com.spe.context.evaluation.internal.scoring;

import com.spe.context.evaluation.internal.scoring.calculator.TaskSupplierScore;

/**
 * 试算结果（13 §E18 的 preview 出参）：**不落库**，纯计算。
 *
 * <p>返回的是**整个评估单**的结果（各维度分 + 总分），因为打分页要显示"维度分试算值"，
 * 而总分只有跨全部维度才有意义；目标评分单那一份就在 `taskSupplier.sheets()` 里按 dimensionId 取。
 *
 * @param sheetId 发起试算的评分单
 * @param dimensionId 它的维度 id
 * @param taskSupplier 该评估单的完整试算结果（不完整口径见 13 §E6：score 可能为 null）
 */
public record SheetPreview(long sheetId, long dimensionId, TaskSupplierScore taskSupplier) {}

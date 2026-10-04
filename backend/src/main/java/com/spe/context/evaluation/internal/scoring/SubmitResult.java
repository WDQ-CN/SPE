package com.spe.context.evaluation.internal.scoring;

/**
 * 提交结果（05 §5.5 T2 / 13 §E9）。
 *
 * @param sheetId 已提交的评分单
 * @param taskSupplierId 所属评估单
 * @param taskSupplierStatus 提交后评估单的状态（只有全部维度提交完才会变成 PENDING_APPROVAL）
 * @param submittedIndicators 本次提交覆盖的指标数
 * @param correctedCells 提交时重算发现与库里不一致、已就更正的格子数（E8 的第二道守卫；正常恒为 0）
 */
public record SubmitResult(
    long sheetId,
    long taskSupplierId,
    String taskSupplierStatus,
    int submittedIndicators,
    int correctedCells) {}

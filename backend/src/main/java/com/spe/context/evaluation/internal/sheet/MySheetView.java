package com.spe.context.evaluation.internal.sheet;

import com.spe.context.evaluation.api.SheetStatus;
import java.time.LocalDate;

/**
 * 「我的待办」的一行（13 §E13 / 01 E1）：待办列表要显示任务名、供应商、维度、截止日期与状态。
 *
 * @param sheetId 评分单 id
 * @param taskId 任务 id
 * @param taskName 任务名
 * @param deadline 截止日期（列表按它排序）
 * @param taskSupplierId 评估单 id
 * @param supplierId 供应商 id
 * @param supplierCode 供应商编码（跨上下文经 BT SupplierQueryFacadeBT 取）
 * @param supplierName 供应商名称（同上）
 * @param dimensionId 维度 id
 * @param dimensionName 维度名（从任务的模板**快照**取，不经模板内部）
 * @param status 评分单状态（UNASSIGNED/SCORING/SUBMITTED）
 */
public record MySheetView(
    long sheetId,
    long taskId,
    String taskName,
    LocalDate deadline,
    long taskSupplierId,
    long supplierId,
    String supplierCode,
    String supplierName,
    long dimensionId,
    String dimensionName,
    SheetStatus status,
    /** 评分单版本：打分页提交时原样带回（乐观锁）。 */
    int version) {}

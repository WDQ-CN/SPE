package com.spe.context.evaluation.internal.task;

import com.spe.context.evaluation.api.SheetStatus;

/**
 * 任务详情页的**评分单行**（13 §E18 的补充端点 `GET /api/tasks/{id}/sheets`）。
 *
 * <p>⚠️ 为什么加这个端点：E18 的清单里有「改派」（`PUT /sheets/{id}/assign`），却没有任何端点能
 * **列出任务下的评分单**——采购经理要看「这个供应商这一维度指派给谁、当前什么状态、版本是多少」，
 * 才谈得上改派（改派还要带 `version`）。W6 对接前端时发现这个缺口，做法与 E18 补三处缺口一致：
 * 先补清单（02 §4）再编码。
 *
 * @param assigneeName 指派对象姓名；未指派时为 null（跨上下文经 ` UserQueryFacade` 批量补齐）
 * @param version 评分单乐观锁版本（改派要原样带回）
 */
public record SheetBrief(
    long sheetId,
    long taskSupplierId,
    long supplierId,
    String supplierCode,
    String supplierName,
    long dimensionId,
    String dimensionName,
    Long assigneeId,
    String assigneeName,
    SheetStatus status,
    int version) {}

package com.spe.context.evaluation.internal.task;

/**
 * 发布结果（01 J2 / 05 §5.5 T1）：供审计 detail 与响应体复用同一组数字。
 *
 * @param supplierCount 评估单数（= 勾选的供应商数）
 * @param sheetCount 评分单数（= 评估单数 × 模板维度数）
 * @param assignedCount 自动指派成功的评分单数
 * @param unassignedCount 未指派（建议部门下没有在职评估人）的评分单数
 */
public record PublishResult(
    int supplierCount, int sheetCount, int assignedCount, int unassignedCount) {}

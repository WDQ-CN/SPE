package com.spe.context.evaluation.internal.task;

import java.time.LocalDate;
import java.util.List;

/**
 * 创建/编辑草稿的入参（01 J1）。
 *
 * @param name 任务名称
 * @param year 周期-年
 * @param quarter 周期-季度 1~4
 * @param templateId 所选模板（与 {@code templateVersionId} 一并提交，服务端校验两者归属一致；
 *     `03 §41.2` / `21 §3.W1-2`：前端版本列表曾在慢网下串模板，只靠版本 id 无法发现）
 * @param templateVersionId 绑定的**已发布**模板版本
 * @param deadline 评估截止日期
 * @param supplierIds 勾选的供应商（草稿阶段就落库：它是 J4 唯一性判定的依据，见 {@link TaskService} 的说明）
 * @param approverIds 审批链成员，**顺序即审批顺序**（seq 从 1 起；01 J1 要求至少一级）
 */
public record TaskDraftCommand(
    String name,
    int year,
    int quarter,
    long templateId,
    long templateVersionId,
    LocalDate deadline,
    List<Long> supplierIds,
    List<Long> approverIds) {}

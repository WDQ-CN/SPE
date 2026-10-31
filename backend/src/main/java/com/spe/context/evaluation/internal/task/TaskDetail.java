package com.spe.context.evaluation.internal.task;

import com.spe.context.evaluation.api.EvalTaskStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 任务详情（13 §E18 的 `GET /api/tasks/{id}` —— `02 §4` 的缺口之一）。
 *
 * @param suppliers 勾选的供应商（含编码与名称，供详情页展示）
 * @param approvers 审批链（按 seq 升序）
 * @param sheetCount 评分单总数（发布后才有；草稿为 0）
 * @param unsubmittedSheetCount 未提交的评分单数（看板同口径）
 */
public record TaskDetail(
    long id,
    String name,
    int year,
    int quarter,
    long templateId,
    long templateVersionId,
    LocalDate deadline,
    EvalTaskStatus status,
    int version,
    List<SupplierRef> suppliers,
    List<ApproverRef> approvers,
    long sheetCount,
    long unsubmittedSheetCount) {

  /** 供应商引用（跨上下文经门面取，见 TaskService）。 */
  public record SupplierRef(long id, String code, String name) {}

  /** 审批人引用（姓名经 UserQueryFacade）。 */
  public record ApproverRef(long userId, String name, int seq) {}
}

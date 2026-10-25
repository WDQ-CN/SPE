package com.spe.context.evaluation.internal.approval;

import com.spe.context.evaluation.api.TaskSupplierStatus;
import java.time.LocalDate;

/**
 * 我的审批待办的一行（15 §E8 / 02 §4 的 `GET /my/approvals`）。
 *
 * <p>列表页要能直接跳到审批页：`taskSupplierId` 是跳转键，`version` 是审批动作的乐观锁前置条件
 * （审批通过/驳回必须原样带回），`round` / `currentNode` 用于显示"第几轮、第几级"。
 *
 * @param supplierCode 供应商编码（跨上下文经 SupplierQueryFacade 批量取）
 * @param supplierName 供应商名称（同上）
 */
public record ApprovalTodoView(
    long taskSupplierId,
    long taskId,
    String taskName,
    int year,
    int quarter,
    LocalDate deadline,
    long supplierId,
    String supplierCode,
    String supplierName,
    TaskSupplierStatus status,
    int currentNode,
    int round,
    int version) {}

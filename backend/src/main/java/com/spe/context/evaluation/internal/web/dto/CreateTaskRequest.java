package com.spe.context.evaluation.internal.web.dto;

import com.spe.common.batch.Batches;
import com.spe.context.evaluation.internal.task.ApprovalNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建任务草稿（01 J1 / 13 §E18 的 `POST /api/tasks`）。
 *
 * <p>DTO 只做**形状**校验（非空、长度、范围）；业务规则（模板必须已发布、供应商必须在职在用、
 * 审批人在职、J4 唯一性）留给服务层——两层的分工见 06 §2.7 与 P2 深审的 P1-2 教训。
 */
public record CreateTaskRequest(
    @NotBlank(message = "任务名称不能为空") @Size(max = 100, message = "任务名称不得超过 100 字") String name,
    @Min(value = 2000, message = "年份不合法") @Max(value = 2999, message = "年份不合法") int year,
    @Min(value = 1, message = "季度必须在 1~4 之间") @Max(value = 4, message = "季度必须在 1~4 之间") int quarter,
    @Positive(message = "必须选择模板") long templateId,
    @Positive(message = "必须选择模板版本") long templateVersionId,
    @NotNull(message = "截止日期不能为空") LocalDate deadline,
    @NotEmpty(message = "请至少选择一个供应商")
    @Size(max = Batches.MAX_ROWS, message = "一个任务最多选择 {max} 家供应商")
    List<Long> supplierIds,
    @NotEmpty(message = "审批链至少需要一级")
    @Size(max = ApprovalNode.MAX_LEVELS, message = "审批链最多 {max} 级")
    List<Long> approverIds) {}

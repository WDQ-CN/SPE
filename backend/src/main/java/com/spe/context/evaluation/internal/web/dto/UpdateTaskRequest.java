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
 * 编辑任务草稿（13 §E18 的缺口端点 `PUT /api/tasks/{id}`）：字段与创建一致，外加乐观锁 `version`。
 *
 * <p>只有草稿可编辑：状态不对 → 2020，版本不对 → 3001（13 §E20 的两分口径）。
 */
public record UpdateTaskRequest(
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
    List<Long> approverIds,
    @NotNull(message = "缺少乐观锁版本") Integer version) {}

package com.spe.context.evaluation.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 审批驳回（02 §4 / 15 §E5）：意见必填（01 FR-P1），最长 500 字（与 `approval_record.comment` 同宽）。
 *
 * <p>⚠️ **不在这里用 `@NotBlank`**：空意见必须返回 2029（`APPROVAL_COMMENT_REQUIRED`），而 Bean Validation
 * 失败会被全局处理器翻成 2001。必填判据放在 Service，与"同一条规则只在一处定义"一起守住错误码契约。
 */
public record RejectRequest(
    @NotNull(message = "缺少乐观锁版本") @Min(value = 0, message = "版本号不能为负") Integer version,
    @Size(max = 500, message = "驳回意见长度不得超过 500 字") String comment) {}

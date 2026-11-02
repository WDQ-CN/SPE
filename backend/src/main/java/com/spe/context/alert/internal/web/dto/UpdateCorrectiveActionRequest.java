package com.spe.context.alert.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 编辑整改措施/负责人/截止日期（W3；`PUT /api/corrective-actions/{id}`）。
 *
 * <p>EVALUATOR 只能编辑本人负责的任务，且不能改派负责人——第二层判定在 Service，
 * 保证「只有 ADMIN/MANAGER 能指派」不依赖前端隐藏字段。
 */
public record UpdateCorrectiveActionRequest(
    @NotNull(message = "整改负责人不能为空")
        @Positive(message = "整改负责人不合法")
        Long ownerId,
    @NotNull(message = "整改截止日期不能为空") LocalDate dueDate,
    @NotBlank(message = "整改措施不能为空")
        @Size(max = 1000, message = "整改措施不得超过 1000 字")
        String measure,
    @Size(max = 500, message = "预期结果不得超过 500 字") String expectedResult,
    @NotNull(message = "缺少乐观锁版本") @Min(value = 0, message = "乐观锁版本不合法")
        Integer version) {}

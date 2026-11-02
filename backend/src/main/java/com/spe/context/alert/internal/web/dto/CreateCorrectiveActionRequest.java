package com.spe.context.alert.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 从预警创建整改（29 §3.5 的 `POST /api/alerts/{id}/corrective-actions`）。
 *
 * <p>负责人只能是在职 EVALUATOR/MANAGER（D1），真正的存在性/角色校验在 Service；
 * 这里只拦形状与长度，避免把 2041 变成 2001。
 */
public record CreateCorrectiveActionRequest(
    @NotNull(message = "整改负责人不能为空")
        @Positive(message = "整改负责人不合法")
        Long ownerId,
    @NotNull(message = "整改截止日期不能为空") LocalDate dueDate,
    @NotBlank(message = "整改措施不能为空")
        @Size(max = 1000, message = "整改措施不得超过 1000 字")
        String measure,
    @Size(max = 500, message = "预期结果不得超过 500 字") String expectedResult) {}

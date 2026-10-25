package com.spe.context.evaluation.internal.result;

import com.spe.context.evaluation.api.EvalTaskStatus;
import java.time.LocalDate;

/**
 * 结果报表入口的一行（01 R2 / 02 §4）：**只包含已有已生效评估单的任务**。
 *
 * @param resultCount 已生效评估单数（前端可显示"已出 N 个供应商结果"）
 */
public record ResultTaskSummary(
    long taskId,
    String taskName,
    int year,
    int quarter,
    LocalDate deadline,
    EvalTaskStatus status,
    long resultCount) {}

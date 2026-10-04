package com.spe.context.evaluation.internal.task;

import com.spe.context.evaluation.api.EvalTaskStatus;
import java.time.LocalDate;

/**
 * 任务列表行（13 §E18 的 `GET /api/tasks`）。
 *
 * <p>刻意不返回实体：实体会带上 P4 才会用到的字段，而列表页只需要这几列（05 §8.3 的精神——
 * 对外给 DTO，别把内部结构漏出去）。
 */
public record TaskSummary(
    long id,
    String name,
    int year,
    int quarter,
    long templateVersionId,
    LocalDate deadline,
    EvalTaskStatus status,
    int version) {}

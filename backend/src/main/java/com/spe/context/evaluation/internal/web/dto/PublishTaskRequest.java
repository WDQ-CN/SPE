package com.spe.context.evaluation.internal.web.dto;

import jakarta.validation.constraints.NotNull;

/** 发布任务（13 §E18 的 `POST /api/tasks/{id}/publish`）：带乐观锁版本，冲突 → 3001。 */
public record PublishTaskRequest(@NotNull(message = "缺少乐观锁版本") Integer version) {}

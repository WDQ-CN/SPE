/**
 * 开放 API 的 evaluation 对外契约（M6 / 05 ADR-8）：读任务/指标 + 按任务/供应商/指标幂等上报。
 *
 * <p>openapi 上下文的 `OpenApiService` 只依赖本包；Controller 不直接依赖，以满足 R-E 的
 * 「Controller 只依赖自身上下文」。
 */
package com.spe.context.evaluation.api.openapi;

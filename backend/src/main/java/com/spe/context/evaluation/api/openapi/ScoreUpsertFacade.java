package com.spe.context.evaluation.api.openapi;

/**
 * 开放 API 的唯一写入口（M6 / 05 ADR-8 / 23 §2 D7）。
 *
 * <p>内部复用 {@code ScoreWriter} 的换算与 upsert；任务必须进行中、sheet 未提交、供应商/指标匹配。
 * 幂等键仍是 `(sheet_id, indicator_id)`，来源为 `API`。
 */
public interface ScoreUpsertFacade {

  OpenApiScoreResult upsert(OpenApiScoreCommand command);
}

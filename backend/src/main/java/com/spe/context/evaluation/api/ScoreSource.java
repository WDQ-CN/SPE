package com.spe.context.evaluation.api;

/**
 * 评分记录的写入来源（02 §3.1 的 `score.source` / 01 §6.7）。
 *
 * <p>三源（手工暂存 / Excel 导入 / 开放 API）从第一天起就收敛到同一个写入口 ` ScoreWriter`（13 §E7），
 * 本枚举就是那条路上的"渠道"标记：它决定 `import_batch` 是否落值。
 */
public enum ScoreSource {
  /** 打分页手工暂存。 */
  MANUAL,
  /** Excel 批量导入（01 E3）：需要带批次号。 */
  IMPORT,
  /** 开放 API 上报（M6 才接入；枚举先留着，避免以后改列取值）。 */
  API
}

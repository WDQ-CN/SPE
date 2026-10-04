package com.spe.context.evaluation.internal.scoring;

/**
 * Excel 导入结果（01 E3）。
 *
 * <p>⚠️ 校验失败**不走这个返回值**：只要有任何一格不合法，就抛 ` IMPORT_CELL_INVALID(2027)` 且**整批不写**
 * （避免留下半截批次，也让批次号始终对应一次完整导入）。这个 record 只描述**成功**的那一次导入。
 *
 * @param batchNo 本次批次号（写进 `score.import_batch`，可据此追溯这一批，13 §E2）
 * @param writtenCells 成功写入（含覆盖）的格子数
 * @param skippedCells 跳过（所属评分单不是「评分中」）的格子数——**跳过不是错误**（13 §E17）
 */
public record ImportReport(String batchNo, int writtenCells, int skippedCells) {

  /** 一行一列的错误（01 E3 的"行-列-原因"）；行号从 **1** 起，列用表头名。 */
  public record CellError(int row, String column, String message) {}
}

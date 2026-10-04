package com.spe.context.evaluation.internal.scoring;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评分记录实体（V11 / 02 §3.1 / 01 §6.7）：**一行一指标**，三源（手工 / Excel / 开放 API）共用一个 upsert 目标。
 *
 * <p>`raw_value` 是录入值，`score_value` 是**写入时**就算好的百分制分（13 §E8）：暂存之后看板/试算
 * 直接读它，不必每次现算；提交时再重算一遍做"防篡改 + 防陈旧"的第二道守卫。
 *
 * <p>没有 version 列是**刻意的**（02 §3.1）：同一格并发写按"后写覆盖"处理（01 §6.7 的重复导入覆盖），
 * 状态守卫落在 `SHEET.status` 上。
 */
@Data
@TableName("score")
public class Score {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long sheetId;

  private Long indicatorId;

  private BigDecimal rawValue;

  private BigDecimal scoreValue;

  /** 来源：MANUAL / IMPORT / API（01 §6.7 的"最后一次写入渠道"）。 */
  private String source;

  /** Excel 批次号：仅本次写入来自 IMPORT 时写，其它来源置 NULL（13 §E2）。 */
  private String importBatch;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}

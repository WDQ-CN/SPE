package com.spe.context.alert.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 预警记录（V14 / 22 §2 D1）：alert 上下文唯一一张写表。 */
@Data
@TableName("alert")
public class Alert {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskSupplierId;

  private Long supplierId;

  private Integer year;

  private Integer quarter;

  /** TOTAL_LOW / KEY_DIMENSION_LOW / TREND_DECLINE（{@link AlertTypes}）。 */
  private String type;

  private String title;

  private String content;

  /** 关键维度预警的维度 id；总分类/趋势类为空（V1004；D4/D8）。 */
  private Long dimensionId;

  /** 触发预警时的基线分：总分类为总分、维度类为维度分（V1004）。 */
  private BigDecimal baselineScore;

  /** 整改目标分：总分类为总分阈值、维度类为关键维度阈值、趋势类为基线分（V1004；D4）。 */
  private BigDecimal targetScore;

  /** UNREAD / HANDLED / SUPERSEDED。 */
  private String status;

  private Long handledBy;

  private String handledComment;

  private LocalDateTime handledAt;

  /** 被哪条新预警取代；重算未命中则为空（22 §2 D2）。 */
  private Long supersededBy;

  private LocalDateTime createdAt;
}

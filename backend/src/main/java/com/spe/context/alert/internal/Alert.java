package com.spe.context.alert.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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

  /** UNREAD / HANDLED / SUPERSEDED。 */
  private String status;

  private Long handledBy;

  private String handledComment;

  private LocalDateTime handledAt;

  /** 被哪条新预警取代；重算未命中则为空（22 §2 D2）。 */
  private Long supersededBy;

  private LocalDateTime createdAt;
}

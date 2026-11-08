package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 寻源定标（V1017；docs/erp/55 §3.3）。 */
@Data
@TableName("sourcing_award")
public class SourcingAward {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String awardNo;
  private Long rfqId;
  private Long supplierId;
  private Long quoteId;
  private BigDecimal awardAmount;
  private String currency;
  private SourcingAwardStatus status;
  private String reason;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
}

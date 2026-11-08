package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 竞价结果（V1020；每 lot 一条）。 */
@Data
@TableName("auction_result")
public class AuctionResult {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long auctionId;
  private Long lotId;
  private Long supplierId;
  private Long bidId;
  private BigDecimal amount;
  private AuctionResultStatus status;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}

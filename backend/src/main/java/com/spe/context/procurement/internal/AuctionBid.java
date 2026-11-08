package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 竞价出价（V1014，不可变）。 */
@Data
@TableName("auction_bid")
public class AuctionBid {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long auctionId;
  private Long lotId;
  private Long supplierId;
  private BigDecimal amount;
  private AuctionBidStatus status;
  private String idempotencyKey;
  private String requestHash;
  private Boolean belowReserve;
  private String clientIp;
  private LocalDateTime submittedAt;
}

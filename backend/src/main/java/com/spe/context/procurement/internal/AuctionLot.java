package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 竞价批次（V1014）。 */
@Data
@TableName("auction_lot")
public class AuctionLot {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long auctionId;
  private Integer lineNo;
  private Long itemId;
  private BigDecimal qty;
  private String uom;
  private BigDecimal startPrice;
  private BigDecimal reservePrice;
  private BigDecimal minDecrement;
  private BigDecimal currentLowestAmount;
  private Long currentLowestBidId;
  private Integer bidCount;
  private Integer version;
}

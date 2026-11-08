package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 竞价防狙击延期记录。 */
@Data
@TableName("auction_extension")
public class AuctionExtension {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long auctionId;
  private Long lotId;
  private LocalDateTime extendedAt;
  private LocalDateTime oldEndAt;
  private LocalDateTime newEndAt;
  private String reason;
}

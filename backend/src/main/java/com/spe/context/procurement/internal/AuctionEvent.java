package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 竞价事件（V1014）。 */
@Data
@TableName("auction_event")
public class AuctionEvent {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String auctionNo;
  private Long companyId;
  private String title;
  private String currency;
  private Boolean sealed;
  private Integer antiSnipingSeconds;
  private Integer maxExtensions;
  private LocalDateTime startAt;
  private LocalDateTime endAt;
  private Integer extensionCount;
  private String resultVisibility;
  private AuctionStatus status;
  private Long createdBy;
  private Integer version;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}

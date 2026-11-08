package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 竞价邀请（V1014）。 */
@Data
@TableName("auction_invitation")
public class AuctionInvitation {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long auctionId;
  private Long supplierId;
  private AuctionInvitationStatus status;
  private Long invitedBy;
  private LocalDateTime invitedAt;
  private LocalDateTime acceptedAt;
}

package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 询价邀请。 */
@Data
@TableName("sourcing_invitation")
public class SourcingInvitation {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long rfqId;
  private Long supplierId;
  private InvitationStatus status;
  private LocalDateTime invitedAt;
  private LocalDateTime viewedAt;
  private LocalDateTime respondedAt;
}

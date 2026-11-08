package com.spe.context.supplier.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 供应商门户邀请 Token（V1019；只存哈希）。 */
@Data
@TableName("supplier_portal_invitation")
public class SupplierPortalInvitation {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long supplierId;
  private String tokenHash;
  private SupplierPortalInvitationStatus status;
  private LocalDateTime expiresAt;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime acceptedAt;
  private Long portalUserId;
}

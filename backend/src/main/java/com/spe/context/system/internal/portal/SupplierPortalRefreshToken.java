package com.spe.context.system.internal.portal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 门户刷新令牌（只存哈希）。 */
@Data
@TableName("supplier_portal_refresh_token")
public class SupplierPortalRefreshToken {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long portalUserId;
  private String tokenHash;
  private LocalDateTime expiresAt;
  private LocalDateTime revokedAt;
  private LocalDateTime createdAt;
}

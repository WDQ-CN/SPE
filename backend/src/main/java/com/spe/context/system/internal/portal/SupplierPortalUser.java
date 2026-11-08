package com.spe.context.system.internal.portal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 供应商门户账号（V1014；与内部 sys_user 分离）。 */
@Data
@TableName("supplier_portal_user")
public class SupplierPortalUser {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long supplierId;
  private String loginName;
  private String passwordHash;
  private SupplierPortalUserStatus status;
  private Integer failedAttempts;
  private LocalDateTime lockedUntil;
  private LocalDateTime lastLoginAt;
  private Integer version;
  private LocalDateTime createdAt;
}

package com.spe.context.system.internal.token;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.system.api.ApiTokenStatus;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 开放 API Token 实体（V1001 / 23 §2 D2）。
 *
 * <p>只存 `token_hash` 与 `token_prefix`，不存明文；实体只在 system.internal 内流转，Controller 返回 View。
 */
@Data
@TableName("api_token")
public class ApiToken {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;

  private String tokenHash;

  private String tokenPrefix;

  private ApiTokenStatus status;

  private LocalDateTime lastUsedAt;

  private Long createdBy;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  private Integer version;
}

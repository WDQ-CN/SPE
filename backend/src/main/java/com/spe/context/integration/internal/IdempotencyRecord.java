package com.spe.context.integration.internal;

import java.time.LocalDateTime;
import lombok.Data;

/** 幂等记录（联合主键，不用 MyBatis-Plus 自动注入）。 */
@Data
public class IdempotencyRecord {

  private String scope;
  private String idemKey;
  private String requestHash;
  private Integer responseStatus;
  private String responseBody;
  private LocalDateTime createdAt;
  private LocalDateTime expiresAt;
}

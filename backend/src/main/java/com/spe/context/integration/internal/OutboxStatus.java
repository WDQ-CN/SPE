package com.spe.context.integration.internal;

/** outbox 状态。 */
public enum OutboxStatus {
  PENDING,
  SENT,
  FAILED,
  DEAD
}

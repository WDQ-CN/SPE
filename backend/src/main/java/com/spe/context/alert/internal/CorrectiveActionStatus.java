package com.spe.context.alert.internal;

/** 整改任务状态（V1004 列注释的 Java 镜像；只增不改）。 */
public enum CorrectiveActionStatus {
  OPEN,
  IN_PROGRESS,
  WAITING_VERIFICATION,
  VERIFIED,
  REOPENED,
  CANCELED;

  public boolean isTerminal() {
    return this == VERIFIED || this == CANCELED;
  }
}

package com.spe.context.system.api;

/**
 * 账号状态（01 A4/SM1）：`ENABLED`/`DISABLED`。
 *
 * <p>状态迁移一律经带前置状态条件的 UPDATE 完成（06 §2.4），影响行数为 0 返回 3xxx——
 * 本枚举只描述取值，不承载迁移逻辑。
 */
public enum UserStatus {
  ENABLED,
  DISABLED;

  /** 两态状态机里「另一个」状态，用于拼守卫 UPDATE 的前置条件（10 §E12 的两态约定）。 */
  public UserStatus other() {
    return this == ENABLED ? DISABLED : ENABLED;
  }
}

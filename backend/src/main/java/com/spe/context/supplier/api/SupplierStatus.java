package com.spe.context.supplier.api;

/**
 * 供应商档案状态（01 S2 / `V3__supplier.sql` 的 status 列）：`ENABLED`/`DISABLED`。
 *
 * <p>与 {@code system.api.UserStatus} 值域相同但**语义不同**（档案状态 ↔ 账号状态），故各自持有枚举、
 * 不复用对方类型——否则供应商将来加档位（如黑名单）会污染用户状态（10 §E16）。
 *
 * <p>状态迁移由带前置状态条件的 UPDATE 完成（06 §2.4），本枚举只描述取值与两态推导。
 */
public enum SupplierStatus {
  ENABLED,
  DISABLED;

  /** 两态机里「另一个」状态：守卫 UPDATE 的前置状态由目标状态反推，不查库（照 `UserStatus.other()`）。 */
  public SupplierStatus other() {
    return this == ENABLED ? DISABLED : ENABLED;
  }
}

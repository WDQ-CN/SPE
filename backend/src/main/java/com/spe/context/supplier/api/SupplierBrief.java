package com.spe.context.supplier.api;

/**
 * 供应商的跨上下文只读投影（05 §8.3：门面返回 DTO，实体永不出上下文）。
 *
 * <p>不含 `version`：那是本上下文乐观锁守卫的前置条件、属实现细节，跨上下文只需要档案事实
 * （10 §3 W3 的 R9-17）。
 */
public record SupplierBrief(Long id, String code, String name, String category, SupplierStatus status) {

  public boolean enabled() {
    return status == SupplierStatus.ENABLED;
  }
}

package com.spe.context.supplier.internal;

import com.spe.context.supplier.api.SupplierStatus;

/**
 * 供应商列表行（HTTP 响应投影）。带 `version` 是因为编辑与启停都要求前端回传版本做守卫条件
 * （02 §4 的 `PUT /suppliers/{id}`、`PUT /suppliers/{id}/status`）。
 *
 * <p>与跨上下文的 {@code SupplierBrief} 分开：这里多一个 `version`，它属本上下文实现细节，不外泄。
 */
public record SupplierSummary(
    Long id, String code, String name, String category, SupplierStatus status, Integer version) {

  static SupplierSummary of(Supplier supplier) {
    return new SupplierSummary(
        supplier.getId(),
        supplier.getCode(),
        supplier.getName(),
        supplier.getCategory(),
        supplier.getStatus(),
        supplier.getVersion());
  }
}

package com.spe.context.supplier.internal;

import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.supplier.api.SupplierStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link SupplierQueryFacade} 的实现（05 §8.3）：只读跨上下文查询，实体不出上下文。 */
@Component
@RequiredArgsConstructor
public class SupplierQueryFacadeImpl implements SupplierQueryFacade {

  private final SupplierMapper supplierMapper;

  @Override
  @Transactional(readOnly = true)
  public Optional<SupplierBrief> findById(long supplierId) {
    return supplierMapper.findById(supplierId).map(SupplierQueryFacadeImpl::toBrief);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SupplierBrief> findByIds(Collection<Long> supplierIds) {
    if (supplierIds == null || supplierIds.isEmpty()) {
      return List.of();
    }
    return supplierMapper.findByIds(supplierIds).stream().map(SupplierQueryFacadeImpl::toBrief).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isEnabled(long supplierId) {
    return supplierMapper
        .findById(supplierId)
        .map(supplier -> supplier.getStatus() == SupplierStatus.ENABLED)
        .orElse(false);
  }

  private static SupplierBrief toBrief(Supplier supplier) {
    return new SupplierBrief(
        supplier.getId(),
        supplier.getCode(),
        supplier.getName(),
        supplier.getCategory(),
        supplier.getStatus());
  }
}

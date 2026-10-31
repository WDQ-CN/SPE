package com.spe.context.supplier.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 供应商只读门面（05 §4.3 / §8.3）：跨上下文读取供应商档案的唯一入口，实体与 Mapper 不外泄。
 *
 * <p>P3 创建任务时用它取供应商信息，并据 {@code status} 兑现「停用后不可被新任务选中」（01 S2——
 * P1 只保证状态正确落库，拦截在 P3 选供应商时生效）。写操作不经此门面：供应商维护只由 supplier
 * 自己的 Controller 触发。
 */
public interface SupplierQueryFacade {

  Optional<SupplierBrief> findById(long supplierId);

  /** 按编码取（M6 开放 API 写请求用 code 定位供应商；编码有数据库唯一键）。 */
  Optional<SupplierBrief> findByCode(String code);

  /** 批量取供应商；入参为空返回空列表。供任务发布这类「一次处理多家」的场景使用。 */
  List<SupplierBrief> findByIds(Collection<Long> supplierIds);
}

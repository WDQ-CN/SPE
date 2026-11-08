package com.spe.context.masterdata.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 主数据只读门面（05 §4.3 / §8.3）：采购等上下文校验组织/物料与取有效采购价的唯一入口。
 *
 * <p>只返回投影，实体与 Mapper 不外泄；写操作仍只由 masterdata 自己的 Controller 触发。
 */
public interface MasterDataQueryFacade {

  Optional<CompanyBrief> findCompany(long companyId);

  Optional<PlantBrief> findPlant(long plantId);

  Optional<WarehouseBrief> findWarehouse(long warehouseId);

  Optional<LocationBrief> findLocation(long locationId);

  List<LocationBrief> findLocations(Collection<Long> locationIds);

  Optional<ItemBrief> findItem(long itemId);

  List<ItemBrief> findItems(Collection<Long> itemIds);

  Optional<CustomerBrief> findCustomer(long customerId);

  List<CustomerBrief> findCustomers(Collection<Long> customerIds);

  /**
   * 有效采购价：同币种、数量阶梯命中、价格表与行均在生效期内，取数量阶梯最高者。
   *
   * <p>返回空表示该物料在此日期没有可用的采购价目（不代表价格为零）。
   */
  Optional<BigDecimal> findPurchasePrice(
      long itemId, String uom, BigDecimal qty, String currency, LocalDate date);

  /** 生效的科目映射：同事件取 `valid_from` 最近且不晚于业务日期的一条。 */
  Optional<AccountMappingBrief> findEffectiveAccountMapping(
      long companyId, String eventType, LocalDate date);

  Optional<GlAccountBrief> findAccount(long accountId);

  List<GlAccountBrief> findAccounts(Collection<Long> accountIds);
}

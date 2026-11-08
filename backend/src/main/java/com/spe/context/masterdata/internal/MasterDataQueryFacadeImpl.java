package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.AccountMappingBrief;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.CustomerBrief;
import com.spe.context.masterdata.api.GlAccountBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.LocationBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.PlantBrief;
import com.spe.context.masterdata.api.WarehouseBrief;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link MasterDataQueryFacade} 的实现（05 §8.3）：只读查询，实体不出上下文。 */
@Component
@RequiredArgsConstructor
public class MasterDataQueryFacadeImpl implements MasterDataQueryFacade {

  private final CompanyMapper companyMapper;
  private final PlantMapper plantMapper;
  private final WarehouseMapper warehouseMapper;
  private final WarehouseLocationMapper locationMapper;
  private final ItemMapper itemMapper;
  private final CustomerMapper customerMapper;
  private final PriceLineMapper priceLineMapper;
  private final AccountMappingMapper accountMappingMapper;
  private final GlAccountMapper glAccountMapper;

  @Override
  @Transactional(readOnly = true)
  public Optional<CompanyBrief> findCompany(long companyId) {
    return companyMapper.findById(companyId).map(MasterDataQueryFacadeImpl::toBrief);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PlantBrief> findPlant(long plantId) {
    return plantMapper.findById(plantId).map(MasterDataQueryFacadeImpl::toBrief);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<WarehouseBrief> findWarehouse(long warehouseId) {
    return warehouseMapper.findById(warehouseId).map(MasterDataQueryFacadeImpl::toBrief);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<LocationBrief> findLocation(long locationId) {
    return locationMapper.findById(locationId).map(MasterDataQueryFacadeImpl::toBrief);
  }

  @Override
  @Transactional(readOnly = true)
  public List<LocationBrief> findLocations(Collection<Long> locationIds) {
    if (locationIds == null || locationIds.isEmpty()) {
      return List.of();
    }
    return locationMapper.findByIds(locationIds).stream()
        .map(MasterDataQueryFacadeImpl::toBrief)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ItemBrief> findItem(long itemId) {
    return itemMapper.findById(itemId).map(MasterDataQueryFacadeImpl::toBrief);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ItemBrief> findItems(Collection<Long> itemIds) {
    if (itemIds == null || itemIds.isEmpty()) {
      return List.of();
    }
    return itemMapper.findByIds(itemIds).stream().map(MasterDataQueryFacadeImpl::toBrief).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CustomerBrief> findCustomer(long customerId) {
    return customerMapper.findById(customerId).map(MasterDataQueryFacadeImpl::toBrief);
  }

  @Override
  @Transactional(readOnly = true)
  public List<CustomerBrief> findCustomers(Collection<Long> customerIds) {
    if (customerIds == null || customerIds.isEmpty()) {
      return List.of();
    }
    return customerMapper.findByIds(customerIds).stream()
        .map(MasterDataQueryFacadeImpl::toBrief)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BigDecimal> findPurchasePrice(
      long itemId, String uom, BigDecimal qty, String currency, LocalDate date) {
    if (uom == null || qty == null || currency == null || date == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        priceLineMapper.findEffectivePurchasePrice(itemId, uom, qty, currency, date));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AccountMappingBrief> findEffectiveAccountMapping(
      long companyId, String eventType, LocalDate date) {
    if (eventType == null || eventType.isBlank() || date == null) {
      return Optional.empty();
    }
    return accountMappingMapper
        .findEffective(companyId, eventType, date)
        .map(
            mapping ->
                new AccountMappingBrief(
                    mapping.getId(),
                    mapping.getCompanyId(),
                    mapping.getEventType(),
                    mapping.getDimension(),
                    mapping.getDebitAccountId(),
                    mapping.getCreditAccountId(),
                    mapping.getTaxAccountId(),
                    mapping.getValidFrom()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<GlAccountBrief> findAccount(long accountId) {
    return glAccountMapper.findById(accountId).map(MasterDataQueryFacadeImpl::toBrief);
  }

  @Override
  @Transactional(readOnly = true)
  public List<GlAccountBrief> findAccounts(Collection<Long> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
      return List.of();
    }
    return glAccountMapper.findByIds(accountIds).stream()
        .map(MasterDataQueryFacadeImpl::toBrief)
        .toList();
  }

  private static CompanyBrief toBrief(Company company) {
    return new CompanyBrief(
        company.getId(),
        company.getCode(),
        company.getName(),
        company.getBaseCurrency(),
        company.getStatus());
  }

  private static PlantBrief toBrief(Plant plant) {
    return new PlantBrief(
        plant.getId(), plant.getCompanyId(), plant.getCode(), plant.getName(), plant.getStatus());
  }

  private static WarehouseBrief toBrief(Warehouse warehouse) {
    return new WarehouseBrief(
        warehouse.getId(),
        warehouse.getPlantId(),
        warehouse.getCode(),
        warehouse.getName(),
        warehouse.getStatus());
  }

  private static LocationBrief toBrief(WarehouseLocation location) {
    return new LocationBrief(
        location.getId(),
        location.getWarehouseId(),
        location.getCode(),
        location.getName(),
        location.getStatus());
  }

  private static ItemBrief toBrief(Item item) {
    return new ItemBrief(
        item.getId(),
        item.getCode(),
        item.getName(),
        item.getBaseUom(),
        Boolean.TRUE.equals(item.getLotControlled()),
        Boolean.TRUE.equals(item.getSerialControlled()),
        item.getItemType(),
        item.getValuationMethod(),
        item.getStandardCost(),
        item.getStatus());
  }

  private static CustomerBrief toBrief(Customer customer) {
    return new CustomerBrief(
        customer.getId(),
        customer.getCode(),
        customer.getName(),
        customer.getCurrency(),
        customer.getPaymentTerms(),
        customer.getCreditLimit(),
        customer.getCreditUsed(),
        customer.getStatus());
  }

  private static GlAccountBrief toBrief(GlAccount account) {
    return new GlAccountBrief(
        account.getId(),
        account.getCompanyId(),
        account.getCode(),
        account.getName(),
        account.getAccountType(),
        account.getNormalBalance(),
        account.getStatus());
  }
}

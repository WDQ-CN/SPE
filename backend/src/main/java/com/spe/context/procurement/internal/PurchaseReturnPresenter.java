package com.spe.context.procurement.internal;

import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.LocationBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.WarehouseBrief;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.UserBrief;
import com.spe.context.system.api.UserQueryFacade;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 采购退货视图装配。 */
@Component
@RequiredArgsConstructor
public class PurchaseReturnPresenter {

  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final UserQueryFacade userQueryFacade;
  private final PurchaseOrderMapper purchaseOrderMapper;
  private final PurchaseReturnLineMapper lineMapper;

  @Transactional(readOnly = true)
  public PurchaseReturnView view(PurchaseReturn purchaseReturn) {
    CompanyBrief company = masterDataQueryFacade.findCompany(purchaseReturn.getCompanyId()).orElse(null);
    SupplierBrief supplier = supplierQueryFacade.findById(purchaseReturn.getSupplierId()).orElse(null);
    PurchaseOrder order = purchaseOrderMapper.findById(purchaseReturn.getPoId()).orElse(null);
    WarehouseBrief warehouse =
        masterDataQueryFacade.findWarehouse(purchaseReturn.getWarehouseId()).orElse(null);
    UserBrief creator = userQueryFacade.findById(purchaseReturn.getCreatedBy()).orElse(null);
    List<PurchaseReturnLine> lines = lineMapper.findByReturnId(purchaseReturn.getId());
    Map<Long, ItemBrief> items =
        masterDataQueryFacade.findItems(lines.stream().map(PurchaseReturnLine::getItemId).toList()).stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    Map<Long, LocationBrief> locations =
        masterDataQueryFacade.findLocations(
            lines.stream().map(PurchaseReturnLine::getLocationId).filter(id -> id != null && id > 0).toList())
            .stream()
            .collect(Collectors.toMap(LocationBrief::id, Function.identity()));
    return new PurchaseReturnView(
        purchaseReturn.getId(),
        purchaseReturn.getDocNo(),
        purchaseReturn.getCompanyId(),
        company == null ? null : company.name(),
        purchaseReturn.getSupplierId(),
        supplier == null ? null : supplier.name(),
        purchaseReturn.getPoId(),
        order == null ? null : order.getDocNo(),
        purchaseReturn.getWarehouseId(),
        warehouse == null ? null : warehouse.name(),
        purchaseReturn.getReturnDate(),
        purchaseReturn.getCurrency(),
        purchaseReturn.getNetAmount(),
        Boolean.TRUE.equals(purchaseReturn.getInvoiced()),
        purchaseReturn.getStatus(),
        purchaseReturn.getReason(),
        purchaseReturn.getCreatedBy(),
        creator == null ? null : creator.name(),
        purchaseReturn.getVersion(),
        purchaseReturn.getCreatedAt(),
        lines.stream()
            .map(
                line -> {
                  ItemBrief item = items.get(line.getItemId());
                  LocationBrief location =
                      line.getLocationId() == null ? null : locations.get(line.getLocationId());
                  return new PurchaseReturnLineView(
                      line.getId(),
                      line.getLineNo(),
                      line.getPoLineId(),
                      line.getItemId(),
                      item == null ? null : item.code(),
                      item == null ? null : item.name(),
                      line.getQty(),
                      line.getUom(),
                      line.getUnitCost(),
                      line.getAmount(),
                      line.getLotNo(),
                      line.getLocationId(),
                      location == null ? null : location.name());
                })
            .toList());
  }

  @Transactional(readOnly = true)
  public List<PurchaseReturnSummaryView> summaries(List<PurchaseReturn> rows) {
    Map<Long, CompanyBrief> companies =
        rows.stream()
            .map(PurchaseReturn::getCompanyId)
            .filter(Objects::nonNull)
            .distinct()
            .map(id -> masterDataQueryFacade.findCompany(id).orElse(null))
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(CompanyBrief::id, Function.identity()));
    Map<Long, SupplierBrief> suppliers =
        supplierQueryFacade.findByIds(rows.stream().map(PurchaseReturn::getSupplierId).toList()).stream()
            .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
    Map<Long, PurchaseOrder> orders =
        purchaseOrderMapper.findByIds(rows.stream().map(PurchaseReturn::getPoId).toList()).stream()
            .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
    Map<Long, WarehouseBrief> warehouses = warehousesOf(rows.stream().map(PurchaseReturn::getWarehouseId).toList());
    return rows.stream()
        .map(
            row -> {
              CompanyBrief company = companies.get(row.getCompanyId());
              SupplierBrief supplier = suppliers.get(row.getSupplierId());
              PurchaseOrder order = orders.get(row.getPoId());
              WarehouseBrief warehouse = warehouses.get(row.getWarehouseId());
              return new PurchaseReturnSummaryView(
                  row.getId(),
                  row.getDocNo(),
                  row.getCompanyId(),
                  company == null ? null : company.name(),
                  row.getSupplierId(),
                  supplier == null ? null : supplier.name(),
                  row.getPoId(),
                  order == null ? null : order.getDocNo(),
                  row.getWarehouseId(),
                  warehouse == null ? null : warehouse.name(),
                  row.getReturnDate(),
                  row.getCurrency(),
                  row.getNetAmount(),
                  Boolean.TRUE.equals(row.getInvoiced()),
                  row.getStatus());
            })
        .toList();
  }

  private Map<Long, WarehouseBrief> warehousesOf(Collection<Long> ids) {
    return ids.stream()
        .filter(Objects::nonNull)
        .distinct()
        .map(id -> masterDataQueryFacade.findWarehouse(id).orElse(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(WarehouseBrief::id, Function.identity()));
  }
}

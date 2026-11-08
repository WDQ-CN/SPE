package com.spe.context.procurement.internal;

import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.LocationBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.PlantBrief;
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

/** P2P 视图装配：批量取跨上下文名称，避免逐行回查（列表页 N+1）。 */
@Component
@RequiredArgsConstructor
public class ProcurementPresenter {

  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final UserQueryFacade userQueryFacade;
  private final PurchaseRequisitionMapper requisitionMapper;
  private final PurchaseRequisitionLineMapper requisitionLineMapper;
  private final PurchaseOrderMapper purchaseOrderMapper;
  private final PurchaseOrderLineMapper purchaseOrderLineMapper;
  private final GoodsReceiptLineMapper goodsReceiptLineMapper;

  @Transactional(readOnly = true)
  public RequisitionView requisition(PurchaseRequisition requisition) {
    return requisition(requisition, requisitionLineMapper.findByRequisitionId(requisition.getId()));
  }

  @Transactional(readOnly = true)
  public RequisitionView requisition(
      PurchaseRequisition requisition, List<PurchaseRequisitionLine> lines) {
    CompanyBrief company = masterDataQueryFacade.findCompany(requisition.getCompanyId()).orElse(null);
    UserBrief requester = userQueryFacade.findById(requisition.getRequesterId()).orElse(null);
    Map<Long, ItemBrief> items = itemsOf(lines.stream().map(PurchaseRequisitionLine::getItemId).toList());
    return new RequisitionView(
        requisition.getId(),
        requisition.getDocNo(),
        requisition.getCompanyId(),
        company == null ? null : company.name(),
        requisition.getRequesterId(),
        requester == null ? null : requester.name(),
        requisition.getNeedDate(),
        requisition.getCurrency(),
        requisition.getAmount(),
        requisition.getStatus(),
        requisition.getVersion(),
        requisition.getCreatedAt(),
        lines.stream().map(line -> requisitionLine(line, items)).toList());
  }

  @Transactional(readOnly = true)
  public List<RequisitionSummaryView> requisitions(List<PurchaseRequisition> rows) {
    Map<Long, CompanyBrief> companies = companiesOf(rows.stream().map(PurchaseRequisition::getCompanyId).toList());
    Map<Long, UserBrief> users = usersOf(rows.stream().map(PurchaseRequisition::getRequesterId).toList());
    return rows.stream()
        .map(
            row -> {
              CompanyBrief company = companies.get(row.getCompanyId());
              UserBrief requester = users.get(row.getRequesterId());
              return new RequisitionSummaryView(
                  row.getId(),
                  row.getDocNo(),
                  row.getCompanyId(),
                  company == null ? null : company.name(),
                  row.getRequesterId(),
                  requester == null ? null : requester.name(),
                  row.getNeedDate(),
                  row.getCurrency(),
                  row.getAmount(),
                  row.getStatus(),
                  row.getVersion(),
                  row.getCreatedAt());
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public PurchaseOrderView purchaseOrder(PurchaseOrder order) {
    return purchaseOrder(order, purchaseOrderLineMapper.findByPoId(order.getId()));
  }

  @Transactional(readOnly = true)
  public PurchaseOrderView purchaseOrder(PurchaseOrder order, List<PurchaseOrderLine> lines) {
    CompanyBrief company = masterDataQueryFacade.findCompany(order.getCompanyId()).orElse(null);
    SupplierBrief supplier = supplierQueryFacade.findById(order.getSupplierId()).orElse(null);
    PlantBrief plant = masterDataQueryFacade.findPlant(order.getPlantId()).orElse(null);
    UserBrief creator = userQueryFacade.findById(order.getCreatedBy()).orElse(null);
    UserBrief approver =
        order.getApprovedBy() == null ? null : userQueryFacade.findById(order.getApprovedBy()).orElse(null);
    String sourcePrDocNo =
        order.getSourcePrId() == null
            ? null
            : requisitionMapper.findById(order.getSourcePrId()).map(PurchaseRequisition::getDocNo).orElse(null);
    Map<Long, ItemBrief> items = itemsOf(lines.stream().map(PurchaseOrderLine::getItemId).toList());
    return new PurchaseOrderView(
        order.getId(),
        order.getDocNo(),
        order.getCompanyId(),
        company == null ? null : company.name(),
        order.getSupplierId(),
        supplier == null ? null : supplier.name(),
        order.getPlantId(),
        plant == null ? null : plant.name(),
        order.getSourcePrId(),
        sourcePrDocNo,
        order.getCurrency(),
        order.getPaymentTerms(),
        order.getAmount(),
        order.getStatus(),
        order.getCreatedBy(),
        creator == null ? null : creator.name(),
        order.getApprovedBy(),
        approver == null ? null : approver.name(),
        order.getApprovedAt(),
        order.getVersion(),
        order.getCreatedAt(),
        lines.stream().map(line -> purchaseOrderLine(line, items)).toList());
  }

  @Transactional(readOnly = true)
  public List<PurchaseOrderSummaryView> purchaseOrders(List<PurchaseOrder> rows) {
    Map<Long, CompanyBrief> companies = companiesOf(rows.stream().map(PurchaseOrder::getCompanyId).toList());
    Map<Long, SupplierBrief> suppliers =
        suppliersOf(rows.stream().map(PurchaseOrder::getSupplierId).toList());
    Map<Long, PlantBrief> plants = plantsOf(rows.stream().map(PurchaseOrder::getPlantId).toList());
    return rows.stream()
        .map(
            row -> {
              CompanyBrief company = companies.get(row.getCompanyId());
              SupplierBrief supplier = suppliers.get(row.getSupplierId());
              PlantBrief plant = plants.get(row.getPlantId());
              return new PurchaseOrderSummaryView(
                  row.getId(),
                  row.getDocNo(),
                  row.getCompanyId(),
                  company == null ? null : company.name(),
                  row.getSupplierId(),
                  supplier == null ? null : supplier.name(),
                  row.getPlantId(),
                  plant == null ? null : plant.name(),
                  row.getCurrency(),
                  row.getAmount(),
                  row.getStatus(),
                  row.getVersion(),
                  row.getCreatedAt());
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public GoodsReceiptView goodsReceipt(GoodsReceipt receipt) {
    List<GoodsReceiptLine> lines = goodsReceiptLineMapper.findByGrId(receipt.getId());
    PurchaseOrder order = purchaseOrderMapper.findById(receipt.getPoId()).orElse(null);
    CompanyBrief company = masterDataQueryFacade.findCompany(receipt.getCompanyId()).orElse(null);
    WarehouseBrief warehouse = masterDataQueryFacade.findWarehouse(receipt.getWarehouseId()).orElse(null);
    UserBrief creator = userQueryFacade.findById(receipt.getCreatedBy()).orElse(null);
    UserBrief reverser =
        receipt.getReversedBy() == null
            ? null
            : userQueryFacade.findById(receipt.getReversedBy()).orElse(null);
    Map<Long, ItemBrief> items = itemsOf(lines.stream().map(GoodsReceiptLine::getItemId).toList());
    Map<Long, LocationBrief> locations =
        masterDataQueryFacade.findLocations(
            lines.stream().map(GoodsReceiptLine::getLocationId).filter(id -> id != null && id > 0).toList())
            .stream()
            .collect(Collectors.toMap(LocationBrief::id, Function.identity()));
    return new GoodsReceiptView(
        receipt.getId(),
        receipt.getDocNo(),
        receipt.getPoId(),
        order == null ? null : order.getDocNo(),
        receipt.getCompanyId(),
        company == null ? null : company.name(),
        receipt.getWarehouseId(),
        warehouse == null ? null : warehouse.name(),
        receipt.getStatus(),
        receipt.getCreatedBy(),
        creator == null ? null : creator.name(),
        receipt.getReceivedAt(),
        receipt.getReversedBy(),
        reverser == null ? null : reverser.name(),
        receipt.getReversedAt(),
        receipt.getReverseReason(),
        receipt.getVersion(),
        lines.stream().map(line -> goodsReceiptLine(line, items, locations)).toList());
  }

  @Transactional(readOnly = true)
  public List<GoodsReceiptSummaryView> goodsReceipts(List<GoodsReceipt> rows) {
    Map<Long, CompanyBrief> companies = companiesOf(rows.stream().map(GoodsReceipt::getCompanyId).toList());
    Map<Long, WarehouseBrief> warehouses =
        warehousesOf(rows.stream().map(GoodsReceipt::getWarehouseId).toList());
    Map<Long, PurchaseOrder> orders = ordersOf(rows.stream().map(GoodsReceipt::getPoId).toList());
    return rows.stream()
        .map(
            row -> {
              CompanyBrief company = companies.get(row.getCompanyId());
              WarehouseBrief warehouse = warehouses.get(row.getWarehouseId());
              PurchaseOrder order = orders.get(row.getPoId());
              return new GoodsReceiptSummaryView(
                  row.getId(),
                  row.getDocNo(),
                  row.getPoId(),
                  order == null ? null : order.getDocNo(),
                  row.getCompanyId(),
                  company == null ? null : company.name(),
                  row.getWarehouseId(),
                  warehouse == null ? null : warehouse.name(),
                  row.getStatus(),
                  row.getReceivedAt());
            })
        .toList();
  }

  private Map<Long, CompanyBrief> companiesOf(Collection<Long> ids) {
    return ids.stream()
        .filter(Objects::nonNull)
        .distinct()
        .map(id -> masterDataQueryFacade.findCompany(id).orElse(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(CompanyBrief::id, Function.identity()));
  }

  private Map<Long, PlantBrief> plantsOf(Collection<Long> ids) {
    return ids.stream()
        .filter(Objects::nonNull)
        .distinct()
        .map(id -> masterDataQueryFacade.findPlant(id).orElse(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(PlantBrief::id, Function.identity()));
  }

  private Map<Long, WarehouseBrief> warehousesOf(Collection<Long> ids) {
    return ids.stream()
        .filter(Objects::nonNull)
        .distinct()
        .map(id -> masterDataQueryFacade.findWarehouse(id).orElse(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(WarehouseBrief::id, Function.identity()));
  }

  private Map<Long, SupplierBrief> suppliersOf(Collection<Long> ids) {
    return supplierQueryFacade.findByIds(ids).stream()
        .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
  }

  private Map<Long, UserBrief> usersOf(Collection<Long> ids) {
    return userQueryFacade.findByIds(ids).stream()
        .collect(Collectors.toMap(UserBrief::id, Function.identity()));
  }

  private Map<Long, PurchaseOrder> ordersOf(Collection<Long> ids) {
    List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
    return distinct.isEmpty()
        ? Map.of()
        : purchaseOrderMapper.findByIds(distinct).stream()
            .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
  }

  private Map<Long, ItemBrief> itemsOf(Collection<Long> ids) {
    return masterDataQueryFacade.findItems(ids).stream()
        .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
  }

  private static RequisitionLineView requisitionLine(
      PurchaseRequisitionLine line, Map<Long, ItemBrief> items) {
    ItemBrief item = items.get(line.getItemId());
    return new RequisitionLineView(
        line.getId(),
        line.getLineNo(),
        line.getItemId(),
        item == null ? null : item.code(),
        item == null ? null : item.name(),
        line.getQty(),
        line.getUom(),
        line.getEstimatedPrice(),
        line.getAmount());
  }

  private static PurchaseOrderLineView purchaseOrderLine(
      PurchaseOrderLine line, Map<Long, ItemBrief> items) {
    ItemBrief item = items.get(line.getItemId());
    return new PurchaseOrderLineView(
        line.getId(),
        line.getLineNo(),
        line.getItemId(),
        item == null ? null : item.code(),
        item == null ? null : item.name(),
        line.getQty(),
        line.getUom(),
        line.getPrice(),
        line.getAmount(),
        line.getReceivedQty(),
        line.getInvoicedQty());
  }

  private static GoodsReceiptLineView goodsReceiptLine(
      GoodsReceiptLine line, Map<Long, ItemBrief> items, Map<Long, LocationBrief> locations) {
    ItemBrief item = items.get(line.getItemId());
    LocationBrief location =
        line.getLocationId() == null ? null : locations.get(line.getLocationId());
    return new GoodsReceiptLineView(
        line.getId(),
        line.getLineNo(),
        line.getPoLineId(),
        line.getItemId(),
        item == null ? null : item.code(),
        item == null ? null : item.name(),
        line.getQty(),
        line.getUom(),
        line.getLotNo(),
        line.getLocationId(),
        location == null ? null : location.name());
  }
}

package com.spe.context.inventory.internal;

import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.LocationBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.PlantBrief;
import com.spe.context.masterdata.api.WarehouseBrief;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 库存视图装配：物料/组织名称批量取。 */
@Component
@RequiredArgsConstructor
public class InventoryPresenter {

  private final MasterDataQueryFacade masterDataQueryFacade;
  private final InventoryLotMapper lotMapper;

  @Transactional(readOnly = true)
  public List<InventoryStockView> stocks(List<InventoryStock> rows) {
    Map<Long, ItemBrief> items =
        masterDataQueryFacade.findItems(rows.stream().map(InventoryStock::getItemId).toList()).stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    Map<Long, InventoryLot> lots = lotsOf(rows.stream().map(InventoryStock::getLotId).toList());
    Map<Long, LocationBrief> locations =
        locationsOf(rows.stream().map(InventoryStock::getLocationId).toList());
    return rows.stream()
        .map(
            row -> {
              CompanyBrief company = masterDataQueryFacade.findCompany(row.getCompanyId()).orElse(null);
              PlantBrief plant = masterDataQueryFacade.findPlant(row.getPlantId()).orElse(null);
              WarehouseBrief warehouse =
                  masterDataQueryFacade.findWarehouse(row.getWarehouseId()).orElse(null);
              ItemBrief item = items.get(row.getItemId());
              InventoryLot lot = row.getLotId() == null ? null : lots.get(row.getLotId());
              LocationBrief location =
                  row.getLocationId() == null ? null : locations.get(row.getLocationId());
              return new InventoryStockView(
                  row.getId(),
                  row.getCompanyId(),
                  company == null ? null : company.name(),
                  row.getPlantId(),
                  plant == null ? null : plant.name(),
                  row.getWarehouseId(),
                  warehouse == null ? null : warehouse.name(),
                  row.getItemId(),
                  item == null ? null : item.code(),
                  item == null ? null : item.name(),
                  row.getLotId() == null ? 0L : row.getLotId(),
                  lot == null ? null : lot.getLotNo(),
                  row.getLocationId() == null ? 0L : row.getLocationId(),
                  location == null ? null : location.code(),
                  location == null ? null : location.name(),
                  item == null ? null : item.baseUom(),
                  row.getQtyOnHand(),
                  row.getQtyReserved(),
                  row.getQtyFrozen(),
                  row.availableQty(),
                  row.getTotalValue(),
                  row.getAvgUnitCost(),
                  row.getVersion());
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public List<InventoryMovementView> movements(List<InventoryMovement> rows) {
    Map<Long, ItemBrief> items =
        masterDataQueryFacade.findItems(rows.stream().map(InventoryMovement::getItemId).toList()).stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    Map<Long, InventoryLot> lots = lotsOf(rows.stream().map(InventoryMovement::getLotId).toList());
    Map<Long, LocationBrief> locations =
        locationsOf(rows.stream().map(InventoryMovement::getLocationId).toList());
    return rows.stream()
        .map(
            row -> {
              CompanyBrief company = masterDataQueryFacade.findCompany(row.getCompanyId()).orElse(null);
              PlantBrief plant = masterDataQueryFacade.findPlant(row.getPlantId()).orElse(null);
              WarehouseBrief warehouse =
                  masterDataQueryFacade.findWarehouse(row.getWarehouseId()).orElse(null);
              ItemBrief item = items.get(row.getItemId());
              InventoryLot lot = row.getLotId() == null ? null : lots.get(row.getLotId());
              LocationBrief location =
                  row.getLocationId() == null ? null : locations.get(row.getLocationId());
              return new InventoryMovementView(
                  row.getId(),
                  row.getMovementNo(),
                  row.getCompanyId(),
                  company == null ? null : company.name(),
                  row.getPlantId(),
                  plant == null ? null : plant.name(),
                  row.getWarehouseId(),
                  warehouse == null ? null : warehouse.name(),
                  row.getItemId(),
                  item == null ? null : item.code(),
                  item == null ? null : item.name(),
                  row.getLotId() == null ? 0L : row.getLotId(),
                  lot == null ? null : lot.getLotNo(),
                  row.getLocationId() == null ? 0L : row.getLocationId(),
                  location == null ? null : location.code(),
                  location == null ? null : location.name(),
                  row.getMovementType(),
                  row.getDirection(),
                  row.getQty(),
                  row.getUom(),
                  row.getUnitCost(),
                  row.getAmount(),
                  row.getCurrency(),
                  row.getSourceType(),
                  row.getSourceId(),
                  row.getSourceLineId(),
                  row.getOccurredAt());
            })
        .toList();
  }

  private Map<Long, InventoryLot> lotsOf(Collection<Long> ids) {
    List<Long> distinct =
        ids.stream().filter(id -> id != null && id > 0).distinct().toList();
    return distinct.isEmpty()
        ? Map.of()
        : lotMapper.findByIds(distinct).stream()
            .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));
  }

  private Map<Long, LocationBrief> locationsOf(Collection<Long> ids) {
    List<Long> distinct =
        ids.stream().filter(id -> id != null && id > 0).distinct().toList();
    return distinct.isEmpty()
        ? Map.of()
        : masterDataQueryFacade.findLocations(distinct).stream()
            .collect(Collectors.toMap(LocationBrief::id, Function.identity()));
  }
}

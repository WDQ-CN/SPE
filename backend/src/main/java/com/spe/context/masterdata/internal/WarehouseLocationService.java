package com.spe.context.masterdata.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 仓库库位主数据（V1013；docs/erp/31、33）。 */
@Service
@RequiredArgsConstructor
public class WarehouseLocationService {

  private final WarehouseMapper warehouseMapper;
  private final WarehouseLocationMapper locationMapper;
  private final AuditFacade auditFacade;

  @Transactional(readOnly = true)
  public List<WarehouseLocationView> list(Long warehouseId, String status) {
    MasterDataStatus parsed = MasterDataSupport.status(status);
    return locationMapper.findByWarehouse(warehouseId, parsed).stream()
        .map(WarehouseLocationView::of)
        .toList();
  }

  @Transactional
  public WarehouseLocationView create(
      long warehouseId,
      String code,
      String name,
      String zone,
      String aisle,
      String rack,
      String levelNo,
      String binNo) {
    Warehouse warehouse =
        warehouseMapper
            .findById(warehouseId)
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND));
    if (warehouse.getStatus() != MasterDataStatus.ACTIVE) {
      throw new BizException(ErrorCode.MASTER_DATA_HIERARCHY_INVALID);
    }
    String normalizedCode = MasterDataSupport.upper(code);
    if (locationMapper.countByWarehouseAndCode(warehouseId, normalizedCode) > 0) {
      throw new BizException(ErrorCode.WAREHOUSE_LOCATION_CODE_EXISTS);
    }
    WarehouseLocation location = new WarehouseLocation();
    location.setWarehouseId(warehouseId);
    location.setCode(normalizedCode);
    location.setName(MasterDataSupport.trim(name));
    location.setZone(MasterDataSupport.trim(zone));
    location.setAisle(MasterDataSupport.trim(aisle));
    location.setRack(MasterDataSupport.trim(rack));
    location.setLevelNo(MasterDataSupport.trim(levelNo));
    location.setBinNo(MasterDataSupport.trim(binNo));
    location.setStatus(MasterDataStatus.ACTIVE);
    location.setVersion(0);
    try {
      locationMapper.insert(location);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.WAREHOUSE_LOCATION_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("warehouseId", warehouseId);
    detail.put("code", location.getCode());
    detail.put("name", location.getName());
    auditFacade.record(
        AuditAction.WAREHOUSE_LOCATION_CREATE,
        AuditTargetType.WAREHOUSE_LOCATION,
        location.getId(),
        detail);
    return WarehouseLocationView.of(location);
  }

  @Transactional
  public WarehouseLocationView update(
      long id,
      String code,
      String name,
      String zone,
      String aisle,
      String rack,
      String levelNo,
      String binNo,
      int version) {
    WarehouseLocation current = load(id);
    String normalizedCode = MasterDataSupport.upper(code);
    if (!current.getCode().equals(normalizedCode)
        && locationMapper.countByWarehouseAndCode(current.getWarehouseId(), normalizedCode) > 0) {
      throw new BizException(ErrorCode.WAREHOUSE_LOCATION_CODE_EXISTS);
    }
    try {
      if (locationMapper.updateProfile(
              id,
              normalizedCode,
              MasterDataSupport.trim(name),
              MasterDataSupport.trim(zone),
              MasterDataSupport.trim(aisle),
              MasterDataSupport.trim(rack),
              MasterDataSupport.trim(levelNo),
              MasterDataSupport.trim(binNo),
              version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.WAREHOUSE_LOCATION_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), normalizedCode);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "zone", current.getZone(), zone);
    AuditDetails.putIfChanged(detail, "aisle", current.getAisle(), aisle);
    AuditDetails.putIfChanged(detail, "rack", current.getRack(), rack);
    AuditDetails.putIfChanged(detail, "levelNo", current.getLevelNo(), levelNo);
    AuditDetails.putIfChanged(detail, "binNo", current.getBinNo(), binNo);
    auditFacade.record(
        AuditAction.WAREHOUSE_LOCATION_UPDATE,
        AuditTargetType.WAREHOUSE_LOCATION,
        id,
        detail);
    return new WarehouseLocationView(
        id,
        current.getWarehouseId(),
        normalizedCode,
        MasterDataSupport.trim(name),
        MasterDataSupport.trim(zone),
        MasterDataSupport.trim(aisle),
        MasterDataSupport.trim(rack),
        MasterDataSupport.trim(levelNo),
        MasterDataSupport.trim(binNo),
        current.getStatus(),
        version + 1);
  }

  @Transactional
  public void changeStatus(long id, MasterDataStatus target, int version) {
    WarehouseLocation current = load(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (locationMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.WAREHOUSE_LOCATION_STATUS_CHANGE,
        AuditTargetType.WAREHOUSE_LOCATION,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  private WarehouseLocation load(long id) {
    return locationMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_LOCATION_NOT_FOUND));
  }
}

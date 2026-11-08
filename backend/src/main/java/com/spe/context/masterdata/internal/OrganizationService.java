package com.spe.context.masterdata.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.WarehouseType;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织主数据：公司 → 工厂 → 仓库（docs/erp/31、48、52）。
 *
 * <p>组织层级只允许在创建时绑定；编辑不迁移上级，避免历史交易归属漂移。所有写路径带 `version`
 * 守卫并在同一事务内写审计（与现有 supplier 同形）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

  private final CompanyMapper companyMapper;
  private final PlantMapper plantMapper;
  private final WarehouseMapper warehouseMapper;
  private final AuditFacade auditFacade;

  // ---------- company ----------

  @Transactional(readOnly = true)
  public PageResult<CompanyView> pageCompanies(int page, int size, String keyword, String status) {
    PageQuery query = PageQuery.of(page, size);
    MasterDataStatus parsed = MasterDataSupport.status(status);
    String like = MasterDataSupport.keyword(keyword);
    var rows =
        companyMapper.findPage(like, parsed, query.offset(), query.size()).stream()
            .map(CompanyView::of)
            .toList();
    return PageResult.of(rows, companyMapper.countBy(like, parsed), query.page(), query.size());
  }

  @Transactional
  public CompanyView createCompany(
      String code, String name, String baseCurrency, String taxNo, String country) {
    String normalizedCode = MasterDataSupport.trim(code);
    if (companyMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.COMPANY_CODE_EXISTS);
    }
    Company company = new Company();
    company.setCode(normalizedCode);
    company.setName(MasterDataSupport.trim(name));
    company.setBaseCurrency(MasterDataSupport.upper(baseCurrency));
    company.setTaxNo(MasterDataSupport.trim(taxNo));
    company.setCountry(MasterDataSupport.upper(country));
    company.setStatus(MasterDataStatus.ACTIVE);
    company.setVersion(0);
    try {
      companyMapper.insert(company);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.COMPANY_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("code", company.getCode());
    detail.put("name", company.getName());
    detail.put("baseCurrency", company.getBaseCurrency());
    detail.put("country", company.getCountry());
    auditFacade.record(
        AuditAction.COMPANY_CREATE, AuditTargetType.COMPANY, company.getId(), detail);
    log.info("创建公司 {}（id={}）", normalizedCode, company.getId());
    return CompanyView.of(company);
  }

  @Transactional
  public CompanyView updateCompany(
      long id,
      String code,
      String name,
      String baseCurrency,
      String taxNo,
      String country,
      int version) {
    Company current = loadCompany(id);
    String normalizedCode = MasterDataSupport.trim(code);
    if (!current.getCode().equals(normalizedCode)
        && companyMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.COMPANY_CODE_EXISTS);
    }
    String normalizedCurrency = MasterDataSupport.upper(baseCurrency);
    String normalizedTaxNo = MasterDataSupport.taxNoForUpdate(taxNo, current.getTaxNo());
    String normalizedCountry = MasterDataSupport.upper(country);
    try {
      if (companyMapper.updateProfile(
              id,
              normalizedCode,
              MasterDataSupport.trim(name),
              normalizedCurrency,
              normalizedTaxNo,
              normalizedCountry,
              version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.COMPANY_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), normalizedCode);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "baseCurrency", current.getBaseCurrency(), normalizedCurrency);
    AuditDetails.putIfChanged(detail, "taxNo", current.getTaxNo(), normalizedTaxNo);
    AuditDetails.putIfChanged(detail, "country", current.getCountry(), normalizedCountry);
    auditFacade.record(AuditAction.COMPANY_UPDATE, AuditTargetType.COMPANY, id, detail);
    return new CompanyView(
        id,
        normalizedCode,
        MasterDataSupport.trim(name),
        normalizedCurrency,
        CompanyView.maskTaxNo(normalizedTaxNo),
        normalizedCountry,
        current.getStatus(),
        version + 1);
  }

  @Transactional
  public void changeCompanyStatus(long id, MasterDataStatus target, int version) {
    Company current = loadCompany(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (companyMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.COMPANY_STATUS_CHANGE,
        AuditTargetType.COMPANY,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  // ---------- plant ----------

  @Transactional(readOnly = true)
  public PageResult<PlantView> pagePlants(
      int page, int size, Long companyId, String keyword, String status) {
    PageQuery query = PageQuery.of(page, size);
    MasterDataStatus parsed = MasterDataSupport.status(status);
    String like = MasterDataSupport.keyword(keyword);
    var rows =
        plantMapper.findPage(companyId, like, parsed, query.offset(), query.size()).stream()
            .map(PlantView::of)
            .toList();
    return PageResult.of(
        rows, plantMapper.countBy(companyId, like, parsed), query.page(), query.size());
  }

  @Transactional
  public PlantView createPlant(long companyId, String code, String name, String address) {
    requireActiveCompany(companyId);
    String normalizedCode = MasterDataSupport.trim(code);
    if (plantMapper.countByCompanyAndCode(companyId, normalizedCode) > 0) {
      throw new BizException(ErrorCode.PLANT_CODE_EXISTS);
    }
    Plant plant = new Plant();
    plant.setCompanyId(companyId);
    plant.setCode(normalizedCode);
    plant.setName(MasterDataSupport.trim(name));
    plant.setAddress(MasterDataSupport.trim(address));
    plant.setStatus(MasterDataStatus.ACTIVE);
    plant.setVersion(0);
    try {
      plantMapper.insert(plant);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.PLANT_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("companyId", companyId);
    detail.put("code", plant.getCode());
    detail.put("name", plant.getName());
    auditFacade.record(AuditAction.PLANT_CREATE, AuditTargetType.PLANT, plant.getId(), detail);
    return PlantView.of(plant);
  }

  @Transactional
  public PlantView updatePlant(long id, String code, String name, String address, int version) {
    Plant current = loadPlant(id);
    String normalizedCode = MasterDataSupport.trim(code);
    if (!current.getCode().equals(normalizedCode)
        && plantMapper.countByCompanyAndCode(current.getCompanyId(), normalizedCode) > 0) {
      throw new BizException(ErrorCode.PLANT_CODE_EXISTS);
    }
    try {
      if (plantMapper.updateProfile(
              id, normalizedCode, MasterDataSupport.trim(name), MasterDataSupport.trim(address), version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.PLANT_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), normalizedCode);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "address", current.getAddress(), address);
    auditFacade.record(AuditAction.PLANT_UPDATE, AuditTargetType.PLANT, id, detail);
    return new PlantView(
        id,
        current.getCompanyId(),
        normalizedCode,
        MasterDataSupport.trim(name),
        MasterDataSupport.trim(address),
        current.getStatus(),
        version + 1);
  }

  @Transactional
  public void changePlantStatus(long id, MasterDataStatus target, int version) {
    Plant current = loadPlant(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (plantMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.PLANT_STATUS_CHANGE,
        AuditTargetType.PLANT,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  // ---------- warehouse ----------

  @Transactional(readOnly = true)
  public PageResult<WarehouseView> pageWarehouses(
      int page, int size, Long plantId, String keyword, String status) {
    PageQuery query = PageQuery.of(page, size);
    MasterDataStatus parsed = MasterDataSupport.status(status);
    String like = MasterDataSupport.keyword(keyword);
    var rows =
        warehouseMapper.findPage(plantId, like, parsed, query.offset(), query.size()).stream()
            .map(WarehouseView::of)
            .toList();
    return PageResult.of(
        rows, warehouseMapper.countBy(plantId, like, parsed), query.page(), query.size());
  }

  @Transactional
  public WarehouseView createWarehouse(
      long plantId, String code, String name, WarehouseType warehouseType) {
    requireActivePlant(plantId);
    String normalizedCode = MasterDataSupport.trim(code);
    if (warehouseMapper.countByPlantAndCode(plantId, normalizedCode) > 0) {
      throw new BizException(ErrorCode.WAREHOUSE_CODE_EXISTS);
    }
    Warehouse warehouse = new Warehouse();
    warehouse.setPlantId(plantId);
    warehouse.setCode(normalizedCode);
    warehouse.setName(MasterDataSupport.trim(name));
    warehouse.setWarehouseType(warehouseType);
    warehouse.setStatus(MasterDataStatus.ACTIVE);
    warehouse.setVersion(0);
    try {
      warehouseMapper.insert(warehouse);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.WAREHOUSE_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("plantId", plantId);
    detail.put("code", warehouse.getCode());
    detail.put("name", warehouse.getName());
    detail.put("warehouseType", warehouse.getWarehouseType());
    auditFacade.record(
        AuditAction.WAREHOUSE_CREATE, AuditTargetType.WAREHOUSE, warehouse.getId(), detail);
    return WarehouseView.of(warehouse);
  }

  @Transactional
  public WarehouseView updateWarehouse(
      long id, String code, String name, WarehouseType warehouseType, int version) {
    Warehouse current = loadWarehouse(id);
    String normalizedCode = MasterDataSupport.trim(code);
    if (!current.getCode().equals(normalizedCode)
        && warehouseMapper.countByPlantAndCode(current.getPlantId(), normalizedCode) > 0) {
      throw new BizException(ErrorCode.WAREHOUSE_CODE_EXISTS);
    }
    try {
      if (warehouseMapper.updateProfile(
              id, normalizedCode, MasterDataSupport.trim(name), warehouseType, version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.WAREHOUSE_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), normalizedCode);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "warehouseType", current.getWarehouseType(), warehouseType);
    auditFacade.record(AuditAction.WAREHOUSE_UPDATE, AuditTargetType.WAREHOUSE, id, detail);
    return new WarehouseView(
        id,
        current.getPlantId(),
        normalizedCode,
        MasterDataSupport.trim(name),
        warehouseType,
        current.getStatus(),
        version + 1);
  }

  @Transactional
  public void changeWarehouseStatus(long id, MasterDataStatus target, int version) {
    Warehouse current = loadWarehouse(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (warehouseMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.WAREHOUSE_STATUS_CHANGE,
        AuditTargetType.WAREHOUSE,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  private Company loadCompany(long id) {
    return companyMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
  }

  private Plant loadPlant(long id) {
    return plantMapper.findById(id).orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
  }

  private Warehouse loadWarehouse(long id) {
    return warehouseMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND));
  }

  private void requireActiveCompany(long id) {
    Company company = loadCompany(id);
    if (company.getStatus() != MasterDataStatus.ACTIVE) {
      throw new BizException(ErrorCode.MASTER_DATA_HIERARCHY_INVALID);
    }
  }

  private void requireActivePlant(long id) {
    Plant plant = loadPlant(id);
    if (plant.getStatus() != MasterDataStatus.ACTIVE) {
      throw new BizException(ErrorCode.MASTER_DATA_HIERARCHY_INVALID);
    }
  }
}

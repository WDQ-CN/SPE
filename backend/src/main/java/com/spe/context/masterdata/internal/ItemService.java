package com.spe.context.masterdata.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemType;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.ValuationMethod;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 物料主数据与计量单位换算（docs/erp/31、48、52）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemService {

  private final ItemMapper itemMapper;
  private final ItemUomConversionMapper uomMapper;
  private final AuditFacade auditFacade;

  @Transactional(readOnly = true)
  public PageResult<ItemView> pageItems(int page, int size, String keyword, String itemType, String status) {
    PageQuery query = PageQuery.of(page, size);
    ItemType parsedType = MasterDataSupport.itemType(itemType);
    MasterDataStatus parsedStatus = MasterDataSupport.status(status);
    String like = MasterDataSupport.keyword(keyword);
    var rows =
        itemMapper.findPage(like, parsedType, parsedStatus, query.offset(), query.size()).stream()
            .map(ItemView::of)
            .toList();
    return PageResult.of(
        rows, itemMapper.countBy(like, parsedType, parsedStatus), query.page(), query.size());
  }

  @Transactional
  public ItemView createItem(
      String code,
      String name,
      String spec,
      ItemType itemType,
      String baseUom,
      Boolean lotControlled,
      Boolean serialControlled,
      ValuationMethod valuationMethod,
      BigDecimal standardCost) {
    String normalizedCode = MasterDataSupport.trim(code);
    if (itemMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.ITEM_CODE_EXISTS);
    }
    Item item = new Item();
    item.setCode(normalizedCode);
    item.setName(MasterDataSupport.trim(name));
    item.setSpec(MasterDataSupport.trim(spec));
    item.setItemType(itemType);
    item.setBaseUom(MasterDataSupport.trim(baseUom));
    item.setLotControlled(Boolean.TRUE.equals(lotControlled));
    item.setSerialControlled(Boolean.TRUE.equals(serialControlled));
    item.setValuationMethod(valuationMethod);
    item.setStandardCost(standardCost == null ? BigDecimal.ZERO : standardCost);
    item.setStatus(MasterDataStatus.ACTIVE);
    item.setVersion(0);
    try {
      itemMapper.insert(item);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.ITEM_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("code", item.getCode());
    detail.put("name", item.getName());
    detail.put("itemType", item.getItemType());
    detail.put("baseUom", item.getBaseUom());
    auditFacade.record(AuditAction.ITEM_CREATE, AuditTargetType.ITEM, item.getId(), detail);
    log.info("创建物料 {}（id={}）", normalizedCode, item.getId());
    return ItemView.of(item);
  }

  @Transactional
  public ItemView updateItem(
      long id,
      String code,
      String name,
      String spec,
      ItemType itemType,
      String baseUom,
      Boolean lotControlled,
      Boolean serialControlled,
      ValuationMethod valuationMethod,
      BigDecimal standardCost,
      int version) {
    Item current = loadItem(id);
    String normalizedCode = MasterDataSupport.trim(code);
    if (!current.getCode().equals(normalizedCode) && itemMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.ITEM_CODE_EXISTS);
    }
    boolean lot = Boolean.TRUE.equals(lotControlled);
    boolean serial = Boolean.TRUE.equals(serialControlled);
    try {
      if (itemMapper.updateProfile(
              id,
              normalizedCode,
              MasterDataSupport.trim(name),
              MasterDataSupport.trim(spec),
              itemType,
              MasterDataSupport.trim(baseUom),
              lot,
              serial,
              valuationMethod,
              standardCost == null ? BigDecimal.ZERO : standardCost,
              version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.ITEM_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), normalizedCode);
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "spec", current.getSpec(), spec);
    AuditDetails.putIfChanged(detail, "itemType", current.getItemType(), itemType);
    AuditDetails.putIfChanged(detail, "baseUom", current.getBaseUom(), baseUom);
    AuditDetails.putIfChanged(detail, "lotControlled", current.getLotControlled(), lot);
    AuditDetails.putIfChanged(detail, "serialControlled", current.getSerialControlled(), serial);
    AuditDetails.putIfChanged(
        detail, "valuationMethod", current.getValuationMethod(), valuationMethod);
    AuditDetails.putIfChanged(detail, "standardCost", current.getStandardCost(), standardCost);
    auditFacade.record(AuditAction.ITEM_UPDATE, AuditTargetType.ITEM, id, detail);
    return new ItemView(
        id,
        normalizedCode,
        MasterDataSupport.trim(name),
        MasterDataSupport.trim(spec),
        itemType,
        MasterDataSupport.trim(baseUom),
        lot,
        serial,
        valuationMethod,
        standardCost == null ? BigDecimal.ZERO : standardCost,
        current.getStatus(),
        version + 1);
  }

  @Transactional
  public void changeItemStatus(long id, MasterDataStatus target, int version) {
    Item current = loadItem(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (itemMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.ITEM_STATUS_CHANGE,
        AuditTargetType.ITEM,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  @Transactional(readOnly = true)
  public List<ItemUomView> listUoms(long itemId) {
    loadItem(itemId);
    return uomMapper.findByItemId(itemId).stream().map(ItemUomView::of).toList();
  }

  @Transactional
  public ItemUomView addUom(
      long itemId, String fromUom, String toUom, BigDecimal numerator, BigDecimal denominator) {
    loadItem(itemId);
    String from = MasterDataSupport.upper(fromUom);
    String to = MasterDataSupport.upper(toUom);
    if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
      throw new BizException(ErrorCode.ITEM_UOM_INVALID, "计量单位不能为空");
    }
    if (from.equals(to)) {
      throw new BizException(ErrorCode.ITEM_UOM_INVALID, "源单位与目标单位不能相同");
    }
    if (numerator == null
        || denominator == null
        || numerator.signum() <= 0
        || denominator.signum() <= 0) {
      throw new BizException(ErrorCode.ITEM_UOM_INVALID, "换算分子/分母必须大于 0");
    }
    if (uomMapper.countByKey(itemId, from, to) > 0) {
      throw new BizException(ErrorCode.ITEM_UOM_INVALID, "该换算已存在");
    }
    ItemUomConversion conversion = new ItemUomConversion();
    conversion.setItemId(itemId);
    conversion.setFromUom(from);
    conversion.setToUom(to);
    conversion.setNumerator(numerator);
    conversion.setDenominator(denominator);
    conversion.setVersion(0);
    try {
      uomMapper.insert(conversion);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.ITEM_UOM_INVALID, "该换算已存在");
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("uomFrom", from);
    detail.put("uomTo", to);
    detail.put("numerator", numerator);
    detail.put("denominator", denominator);
    auditFacade.record(AuditAction.ITEM_UOM_ADD, AuditTargetType.ITEM, itemId, detail);
    return ItemUomView.of(conversion);
  }

  @Transactional
  public void removeUom(long itemId, long uomId) {
    loadItem(itemId);
    ItemUomConversion conversion =
        uomMapper
            .findByIdAndItem(uomId, itemId)
            .orElseThrow(() -> new BizException(ErrorCode.ITEM_UOM_NOT_FOUND));
    if (uomMapper.deleteByIdAndItem(uomId, itemId) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("uomFrom", conversion.getFromUom());
    detail.put("uomTo", conversion.getToUom());
    auditFacade.record(AuditAction.ITEM_UOM_REMOVE, AuditTargetType.ITEM, itemId, detail);
  }

  private Item loadItem(long id) {
    return itemMapper.findById(id).orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
  }
}

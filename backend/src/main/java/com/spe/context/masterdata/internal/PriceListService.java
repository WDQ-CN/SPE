package com.spe.context.masterdata.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.PriceListType;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 价格表与价格行（docs/erp/31、48、52）。 */
@Service
@RequiredArgsConstructor
public class PriceListService {

  private final PriceListMapper priceListMapper;
  private final PriceLineMapper priceLineMapper;
  private final ItemMapper itemMapper;
  private final AuditFacade auditFacade;

  @Transactional(readOnly = true)
  public PageResult<PriceListView> page(int page, int size, String keyword, String status) {
    PageQuery query = PageQuery.of(page, size);
    MasterDataStatus parsed = MasterDataSupport.status(status);
    String like = MasterDataSupport.keyword(keyword);
    var rows =
        priceListMapper.findPage(like, parsed, query.offset(), query.size()).stream()
            .map(PriceListView::of)
            .toList();
    return PageResult.of(rows, priceListMapper.countBy(like, parsed), query.page(), query.size());
  }

  @Transactional
  public PriceListView create(
      String code, PriceListType listType, String currency, LocalDate validFrom, LocalDate validTo) {
    validatePeriod(validFrom, validTo);
    String normalizedCode = MasterDataSupport.trim(code);
    if (priceListMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.PRICE_LIST_CODE_EXISTS);
    }
    PriceList priceList = new PriceList();
    priceList.setCode(normalizedCode);
    priceList.setListType(listType);
    priceList.setCurrency(MasterDataSupport.upper(currency));
    priceList.setValidFrom(validFrom);
    priceList.setValidTo(validTo);
    priceList.setStatus(MasterDataStatus.ACTIVE);
    priceList.setVersion(0);
    try {
      priceListMapper.insert(priceList);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.PRICE_LIST_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("code", priceList.getCode());
    detail.put("listType", priceList.getListType());
    detail.put("currency", priceList.getCurrency());
    detail.put("validFrom", validFrom);
    auditFacade.record(
        AuditAction.PRICE_LIST_CREATE, AuditTargetType.PRICE_LIST, priceList.getId(), detail);
    return PriceListView.of(priceList);
  }

  @Transactional
  public PriceListView update(
      long id, String code, String currency, LocalDate validFrom, LocalDate validTo, int version) {
    validatePeriod(validFrom, validTo);
    PriceList current = load(id);
    String normalizedCode = MasterDataSupport.trim(code);
    if (!current.getCode().equals(normalizedCode) && priceListMapper.countByCode(normalizedCode) > 0) {
      throw new BizException(ErrorCode.PRICE_LIST_CODE_EXISTS);
    }
    String normalizedCurrency = MasterDataSupport.upper(currency);
    try {
      if (priceListMapper.updateProfile(
              id, normalizedCode, normalizedCurrency, validFrom, validTo, version)
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.PRICE_LIST_CODE_EXISTS);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "code", current.getCode(), normalizedCode);
    AuditDetails.putIfChanged(detail, "currency", current.getCurrency(), normalizedCurrency);
    AuditDetails.putIfChanged(detail, "validFrom", current.getValidFrom(), validFrom);
    AuditDetails.putIfChanged(detail, "validTo", current.getValidTo(), validTo);
    auditFacade.record(AuditAction.PRICE_LIST_UPDATE, AuditTargetType.PRICE_LIST, id, detail);
    return new PriceListView(
        id,
        normalizedCode,
        current.getListType(),
        normalizedCurrency,
        validFrom,
        validTo,
        current.getStatus(),
        version + 1);
  }

  @Transactional
  public void changeStatus(long id, MasterDataStatus target, int version) {
    PriceList current = load(id);
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    if (priceListMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.PRICE_LIST_STATUS_CHANGE,
        AuditTargetType.PRICE_LIST,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  @Transactional(readOnly = true)
  public List<PriceLineView> listLines(long priceListId) {
    load(priceListId);
    return priceLineMapper.findByList(priceListId).stream().map(PriceLineView::of).toList();
  }

  @Transactional
  public PriceLineView addLine(
      long priceListId,
      long itemId,
      String uom,
      BigDecimal minQty,
      BigDecimal price,
      Boolean taxIncluded,
      LocalDate validFrom,
      LocalDate validTo) {
    PriceList priceList = load(priceListId);
    if (priceList.getStatus() != MasterDataStatus.ACTIVE) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    Item item =
        itemMapper.findById(itemId).orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
    if (item.getStatus() != MasterDataStatus.ACTIVE) {
      throw new BizException(ErrorCode.MASTER_DATA_STATUS_INVALID);
    }
    validatePeriod(validFrom, validTo);
    BigDecimal normalizedMinQty = minQty == null ? BigDecimal.ZERO : minQty;
    String normalizedUom = MasterDataSupport.upper(uom);
    if (priceLineMapper.countByKey(priceListId, itemId, normalizedUom, normalizedMinQty, validFrom)
        > 0) {
      throw new BizException(ErrorCode.PRICE_LINE_INVALID, "该价格阶梯已存在");
    }
    PriceLine line = new PriceLine();
    line.setPriceListId(priceListId);
    line.setItemId(itemId);
    line.setUom(normalizedUom);
    line.setMinQty(normalizedMinQty);
    line.setPrice(price);
    line.setTaxIncluded(Boolean.TRUE.equals(taxIncluded));
    line.setValidFrom(validFrom);
    line.setValidTo(validTo);
    line.setVersion(0);
    try {
      priceLineMapper.insert(line);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.PRICE_LINE_INVALID, "该价格阶梯已存在");
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("itemId", itemId);
    detail.put("uom", normalizedUom);
    detail.put("minQty", normalizedMinQty);
    detail.put("price", price);
    detail.put("validFrom", validFrom);
    auditFacade.record(AuditAction.PRICE_LINE_ADD, AuditTargetType.PRICE_LIST, priceListId, detail);
    return PriceLineView.of(line);
  }

  @Transactional
  public void removeLine(long priceListId, long lineId) {
    load(priceListId);
    PriceLine line =
        priceLineMapper
            .findByIdAndList(lineId, priceListId)
            .orElseThrow(() -> new BizException(ErrorCode.PRICE_LINE_NOT_FOUND));
    if (priceLineMapper.deleteByIdAndList(lineId, priceListId) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("itemId", line.getItemId());
    detail.put("uom", line.getUom());
    detail.put("minQty", line.getMinQty());
    auditFacade.record(AuditAction.PRICE_LINE_REMOVE, AuditTargetType.PRICE_LIST, priceListId, detail);
  }

  private PriceList load(long id) {
    return priceListMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.PRICE_LIST_NOT_FOUND));
  }

  private static void validatePeriod(LocalDate validFrom, LocalDate validTo) {
    if (validTo != null && validTo.isBefore(validFrom)) {
      throw new BizException(ErrorCode.BAD_REQUEST, "失效日期不能早于生效日期");
    }
  }
}

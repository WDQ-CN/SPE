package com.spe.context.procurement.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 内部竞价管理（E2 S4；创建/启动/关闭）。 */
@Service
@RequiredArgsConstructor
public class AuctionService {

  private final AuctionEventMapper eventMapper;
  private final AuctionLotMapper lotMapper;
  private final AuctionInvitationMapper invitationMapper;
  private final AuctionPresenter presenter;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional(readOnly = true)
  public PageResult<AuctionEvent> page(int page, int size, Long companyId, String status) {
    PageQuery query = PageQuery.of(page, size);
    AuctionStatus parsed = null;
    if (status != null && !status.isBlank()) {
      try {
        parsed = AuctionStatus.valueOf(status.trim().toUpperCase());
      } catch (IllegalArgumentException ex) {
        throw new BizException(ErrorCode.BAD_REQUEST, "竞价状态不合法：" + status);
      }
    }
    return PageResult.of(
        eventMapper.findPage(companyId, parsed, query.offset(), query.size()),
        eventMapper.countBy(companyId, parsed),
        query.page(),
        query.size());
  }

  @Transactional
  public AuctionDetailView create(
      long operatorId,
      long companyId,
      String title,
      String currency,
      LocalDateTime startAt,
      LocalDateTime endAt,
      Boolean sealed,
      Integer antiSnipingSeconds,
      Integer maxExtensions,
      List<AuctionLotCommand> lots,
      List<Long> supplierIds) {
    if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
      throw new BizException(ErrorCode.BAD_REQUEST, "竞价结束时间必须晚于开始时间");
    }
    CompanyBrief company =
        masterDataQueryFacade
            .findCompany(companyId)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    if (!company.active()) {
      throw new BizException(ErrorCode.MASTER_DATA_HIERARCHY_INVALID);
    }
    String normalizedCurrency = P2pSupport.upper(currency);
    if (normalizedCurrency == null || normalizedCurrency.isEmpty()) {
      normalizedCurrency = company.baseCurrency();
    }
    if (!company.baseCurrency().equals(normalizedCurrency)) {
      throw new BizException(ErrorCode.PROCUREMENT_CURRENCY_INVALID);
    }
    NormalizedLots normalizedLots = normalizeLots(lots);
    List<Long> normalizedSuppliers = normalizeSuppliers(supplierIds);

    AuctionEvent event = new AuctionEvent();
    event.setAuctionNo(P2pSupport.docNo("AUC"));
    event.setCompanyId(companyId);
    event.setTitle(P2pSupport.required(title, 128, "标题"));
    event.setCurrency(normalizedCurrency);
    event.setSealed(sealed == null || sealed);
    event.setAntiSnipingSeconds(antiSnipingSeconds == null ? 60 : antiSnipingSeconds);
    event.setMaxExtensions(maxExtensions == null ? 3 : maxExtensions);
    event.setStartAt(startAt);
    event.setEndAt(endAt);
    event.setExtensionCount(0);
    event.setResultVisibility("ANONYMOUS");
    event.setStatus(AuctionStatus.DRAFT);
    event.setCreatedBy(operatorId);
    event.setVersion(0);
    eventMapper.insert(event);

    for (AuctionLot lot : normalizedLots.lots()) {
      lot.setAuctionId(event.getId());
      lotMapper.insert(lot);
    }
    for (Long supplierId : normalizedSuppliers) {
      AuctionInvitation invitation = new AuctionInvitation();
      invitation.setAuctionId(event.getId());
      invitation.setSupplierId(supplierId);
      invitation.setStatus(AuctionInvitationStatus.INVITED);
      invitation.setInvitedBy(operatorId);
      invitationMapper.insert(invitation);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("supplierCount", normalizedSuppliers.size());
    detail.put("lotCount", normalizedLots.lots().size());
    detail.put("sealed", event.getSealed());
    detail.put("endAt", endAt);
    auditFacade.record(
        AuditAction.AUCTION_CREATE, AuditTargetType.AUCTION, event.getId(), detail);
    return detail(event.getId());
  }

  @Transactional
  public AuctionDetailView start(long id, int version) {
    AuctionEvent event = load(id);
    if (event.getStatus() != AuctionStatus.DRAFT) {
      throw new BizException(ErrorCode.AUCTION_STATUS_INVALID);
    }
    LocalDateTime now = eventMapper.currentTime();
    if (now.isAfter(event.getEndAt())) {
      throw new BizException(ErrorCode.AUCTION_CLOSED);
    }
    if (eventMapper.updateStatus(id, AuctionStatus.LIVE, AuctionStatus.DRAFT, version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.AUCTION_START, AuditTargetType.AUCTION, id, Map.of("auctionNo", event.getAuctionNo()));
    return detail(id);
  }

  @Transactional
  public AuctionDetailView close(long id, int version) {
    AuctionEvent event = load(id);
    if (event.getStatus() != AuctionStatus.LIVE) {
      throw new BizException(ErrorCode.AUCTION_STATUS_INVALID);
    }
    if (eventMapper.updateStatus(id, AuctionStatus.CLOSED, AuctionStatus.LIVE, version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.AUCTION_CLOSE, AuditTargetType.AUCTION, id, Map.of("auctionNo", event.getAuctionNo()));
    return detail(id);
  }

  @Transactional(readOnly = true)
  public AuctionDetailView detail(long id) {
    AuctionEvent event = load(id);
    return presenter.detail(
        event, lotMapper.findByAuctionId(id), invitationMapper.findByAuctionId(id));
  }

  private AuctionEvent load(long id) {
    return eventMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_FOUND));
  }

  private NormalizedLots normalizeLots(List<AuctionLotCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "至少需要一个竞价批次");
    }
    Set<Long> seen = new HashSet<>();
    List<AuctionLot> lots = new ArrayList<>();
    for (AuctionLotCommand command : commands) {
      if (command == null || command.itemId() <= 0) {
        throw new BizException(ErrorCode.BAD_REQUEST, "竞价物料不能为空");
      }
      if (!seen.add(command.itemId())) {
        throw new BizException(ErrorCode.BAD_REQUEST, "同一物料不能重复竞价");
      }
      ItemBrief item =
          masterDataQueryFacade
              .findItem(command.itemId())
              .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
      if (!item.active()) {
        throw new BizException(ErrorCode.BAD_REQUEST, "物料已停用：" + item.code());
      }
      BigDecimal qty = P2pSupport.qty(command.qty());
      BigDecimal startPrice = P2pSupport.price(command.startPrice());
      BigDecimal minDecrement = P2pSupport.price(command.minDecrement());
      if (qty == null || qty.signum() <= 0
          || startPrice == null || startPrice.signum() <= 0
          || minDecrement == null || minDecrement.signum() <= 0) {
        throw new BizException(ErrorCode.BAD_REQUEST, "数量/起拍价/最小降幅必须大于 0");
      }
      BigDecimal reserve = P2pSupport.price(command.reservePrice());
      if (reserve != null && (reserve.signum() < 0 || reserve.compareTo(startPrice) > 0)) {
        throw new BizException(ErrorCode.BAD_REQUEST, "保留价必须在 0 与起拍价之间");
      }
      AuctionLot lot = new AuctionLot();
      lot.setLineNo(lots.size() + 1);
      lot.setItemId(item.id());
      lot.setQty(qty);
      lot.setUom(command.uom() == null || command.uom().isBlank()
          ? item.baseUom()
          : command.uom().trim().toUpperCase());
      lot.setStartPrice(startPrice);
      lot.setReservePrice(reserve);
      lot.setMinDecrement(minDecrement);
      lot.setBidCount(0);
      lot.setVersion(0);
      lots.add(lot);
    }
    return new NormalizedLots(lots);
  }

  private List<Long> normalizeSuppliers(List<Long> supplierIds) {
    if (supplierIds == null || supplierIds.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "至少邀请一个供应商");
    }
    List<Long> distinct = supplierIds.stream().filter(id -> id != null && id > 0).distinct().toList();
    if (distinct.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "至少邀请一个供应商");
    }
    for (Long supplierId : distinct) {
      SupplierBrief supplier =
          supplierQueryFacade
              .findById(supplierId)
              .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_SUPPLIER_INVALID));
      if (!supplier.enabled()) {
        throw new BizException(ErrorCode.PURCHASE_ORDER_SUPPLIER_INVALID);
      }
    }
    return distinct;
  }

  private record NormalizedLots(List<AuctionLot> lots) {}
}

package com.spe.context.procurement.internal;

import com.spe.common.security.SupplierPortalPrincipal;
import com.spe.common.web.BizException;
import com.spe.common.web.ClientIpResolver;
import com.spe.common.web.ErrorCode;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门户出价裁决（E2 S4；docs/erp/56 §7）。
 *
 * <p>同一 lot 的出价在行锁上串行：服务端时间、最小降幅、幂等键和防狙击在同一事务内判定；
 * 密封模式只返回自己的结果，不泄露最低价/排名/参与人数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuctionBidService {

  private final AuctionEventMapper eventMapper;
  private final AuctionLotMapper lotMapper;
  private final AuctionInvitationMapper invitationMapper;
  private final AuctionBidMapper bidMapper;
  private final AuctionExtensionMapper extensionMapper;
  private final SupplierQueryFacade supplierQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public PortalBidResult submit(
      SupplierPortalPrincipal principal,
      long auctionId,
      long lotId,
      BigDecimal rawAmount,
      String idempotencyKey,
      String clientIp) {
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
      throw new BizException(ErrorCode.BAD_REQUEST, "幂等键不能为空且不得超过 64");
    }
    if (rawAmount == null || rawAmount.signum() <= 0 || rawAmount.scale() > 4) {
      throw new BizException(ErrorCode.AUCTION_BID_INVALID);
    }
    BigDecimal amount = rawAmount.setScale(4, java.math.RoundingMode.HALF_UP);

    AuctionEvent event =
        eventMapper
            .findByIdForUpdate(auctionId)
            .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_FOUND));
    AuctionLot lot =
        lotMapper
            .findByIdAndAuctionForUpdate(lotId, auctionId)
            .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_FOUND));
    SupplierBrief supplier =
        supplierQueryFacade.findById(principal.supplierId()).orElse(null);
    if (supplier == null || !supplier.enabled()) {
      throw new BizException(ErrorCode.AUCTION_NOT_INVITED);
    }
    invitationMapper
        .findByAuctionAndSupplier(auctionId, principal.supplierId())
        .filter(
            item ->
                item.getStatus() == AuctionInvitationStatus.INVITED
                    || item.getStatus() == AuctionInvitationStatus.ACCEPTED)
        .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_INVITED));

    LocalDateTime now = eventMapper.currentTime();
    if (event.getStatus() != AuctionStatus.LIVE) {
      throw new BizException(ErrorCode.AUCTION_STATUS_INVALID);
    }
    if (now.isBefore(event.getStartAt())) {
      throw new BizException(ErrorCode.AUCTION_NOT_STARTED);
    }
    if (!now.isBefore(event.getEndAt())) {
      throw new BizException(ErrorCode.AUCTION_CLOSED);
    }

    String requestHash = AuctionSupport.requestHash(auctionId, lotId, amount.toPlainString());
    AuctionBid existing =
        bidMapper
            .findByKey(lotId, principal.supplierId(), idempotencyKey)
            .orElse(null);
    if (existing != null) {
      if (!existing.getRequestHash().equals(requestHash)) {
        throw new BizException(ErrorCode.AUCTION_IDEMPOTENCY_CONFLICT);
      }
      return result(existing, event, lot, now, false, event.getExtensionCount());
    }

    if (amount.compareTo(lot.getStartPrice()) > 0) {
      throw new BizException(ErrorCode.AUCTION_BID_TOO_HIGH);
    }
    if (lot.getCurrentLowestAmount() != null) {
      BigDecimal floor = lot.getCurrentLowestAmount().subtract(lot.getMinDecrement());
      if (amount.compareTo(floor) > 0) {
        throw new BizException(ErrorCode.AUCTION_BID_TOO_HIGH);
      }
    }

    boolean belowReserve =
        lot.getReservePrice() != null && amount.compareTo(lot.getReservePrice()) < 0;
    AuctionBid bid = new AuctionBid();
    bid.setAuctionId(auctionId);
    bid.setLotId(lotId);
    bid.setSupplierId(principal.supplierId());
    bid.setAmount(amount);
    bid.setStatus(AuctionBidStatus.VALID);
    bid.setIdempotencyKey(idempotencyKey);
    bid.setRequestHash(requestHash);
    bid.setBelowReserve(belowReserve);
    bid.setClientIp(clientIp == null ? ClientIpResolver.current() : clientIp);
    try {
      bidMapper.insert(bid);
    } catch (DuplicateKeyException ex) {
      AuctionBid concurrent =
          bidMapper.findByKey(lotId, principal.supplierId(), idempotencyKey).orElse(null);
      if (concurrent == null) {
        throw ex;
      }
      if (!concurrent.getRequestHash().equals(requestHash)) {
        throw new BizException(ErrorCode.AUCTION_IDEMPOTENCY_CONFLICT);
      }
      return result(concurrent, event, lot, now, false, event.getExtensionCount());
    }

    bidMapper.outbidPrevious(lotId, bid.getId());
    if (lotMapper.updateLowest(lotId, amount, bid.getId(), lot.getVersion()) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    lot.setCurrentLowestAmount(amount);
    lot.setCurrentLowestBidId(bid.getId());
    lot.setBidCount(lot.getBidCount() + 1);

    boolean extended = false;
    int extensionCount = event.getExtensionCount();
    int anti = event.getAntiSnipingSeconds();
    if (anti > 0
        && event.getMaxExtensions() != null
        && extensionCount < event.getMaxExtensions()
        && Duration.between(now, event.getEndAt()).getSeconds() <= anti) {
      LocalDateTime newEnd = event.getEndAt().plusSeconds(anti);
      if (eventMapper.extend(auctionId, newEnd, event.getVersion()) == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
      AuctionExtension extension = new AuctionExtension();
      extension.setAuctionId(auctionId);
      extension.setLotId(lotId);
      extension.setOldEndAt(event.getEndAt());
      extension.setNewEndAt(newEnd);
      extension.setReason("anti-sniping");
      extensionMapper.insert(extension);
      extended = true;
      extensionCount++;
      event.setEndAt(newEnd);
      event.setExtensionCount(extensionCount);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("auctionId", auctionId);
    detail.put("lotId", lotId);
    detail.put("supplierId", principal.supplierId());
    detail.put("amount", amount);
    detail.put("belowReserve", belowReserve);
    detail.put("rank", 1);
    detail.put("sealed", Boolean.TRUE.equals(event.getSealed()));
    auditFacade.recordForOperator(
        principal.portalUserId(),
        "portal:" + principal.loginName(),
        AuditAction.AUCTION_BID_SUBMIT,
        AuditTargetType.AUCTION,
        auctionId,
        detail);
    log.info(
        "门户竞价出价：auction={} lot={} supplier={} amount={}",
        auctionId,
        lotId,
        principal.supplierId(),
        amount);
    return result(bid, event, lot, now, extended, extensionCount);
  }

  private PortalBidResult result(
      AuctionBid bid,
      AuctionEvent event,
      AuctionLot lot,
      LocalDateTime now,
      boolean extended,
      int extensionCount) {
    boolean sealed = Boolean.TRUE.equals(event.getSealed());
    long participants = 0;
    int rank = 0;
    if (!sealed) {
      participants = bidMapper.countDistinctSuppliers(lot.getId());
      rank = (int) (bidMapper.countLowerSuppliers(lot.getId(), bid.getAmount()) + 1);
    }
    return new PortalBidResult(
        bid.getId(),
        lot.getId(),
        bid.getStatus(),
        bid.getAmount(),
        sealed ? null : rank,
        sealed ? null : participants,
        sealed ? null : lot.getCurrentLowestAmount(),
        sealed ? null : lot.getCurrentLowestAmount().subtract(lot.getMinDecrement()),
        now,
        event.getEndAt(),
        sealed,
        extended,
        extensionCount);
  }
}

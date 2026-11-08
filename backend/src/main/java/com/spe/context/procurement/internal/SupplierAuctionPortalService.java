package com.spe.context.procurement.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 门户竞价读侧与出价入口。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierAuctionPortalService {

  private final AuctionEventMapper eventMapper;
  private final AuctionLotMapper lotMapper;
  private final AuctionInvitationMapper invitationMapper;
  private final AuctionBidMapper bidMapper;
  private final AuctionPresenter presenter;
  private final AuctionBidService bidService;
  private final PortalBidRateLimiter rateLimiter;
  private final AuctionStreamService streamService;
  private final AuctionStreamTokenService streamTokenService;
  private final SupplierQueryFacade supplierQueryFacade;

  @Transactional(readOnly = true)
  public PageResult<PortalAuctionSummaryView> page(
      long supplierId, int page, int size, String status) {
    requireSupplierEnabled(supplierId);
    PageQuery query = PageQuery.of(page, size);
    AuctionStatus parsed = P2pSupport.parse(AuctionStatus.class, status, "状态");
    List<AuctionEvent> events =
        eventMapper.findPageForSupplier(supplierId, parsed, query.offset(), query.size());
    return PageResult.of(
        presenter.summaries(events, supplierId),
        eventMapper.countForSupplier(supplierId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public PortalAuctionView detail(long supplierId, long auctionId) {
    AuctionEvent event = requireInvited(supplierId, auctionId);
    List<AuctionLot> lots = lotMapper.findByAuctionId(auctionId);
    List<AuctionBid> ownBids = bidMapper.findByAuctionAndSupplier(auctionId, supplierId);
    return presenter.portalDetail(event, lots, supplierId, ownBids);
  }

  @Transactional(readOnly = true)
  public List<PortalBidView> myBids(long supplierId, long auctionId) {
    requireInvited(supplierId, auctionId);
    return presenter.bids(bidMapper.findByAuctionAndSupplier(auctionId, supplierId));
  }

  /** 状态轮询：客户端用 serverTime 计算时钟偏移，不信任本地倒计时。 */
  @Transactional(readOnly = true)
  public PortalAuctionStateView state(long supplierId, long auctionId, Long lotId) {
    AuctionEvent event = requireInvited(supplierId, auctionId);
    List<AuctionLot> lots = lotMapper.findByAuctionId(auctionId);
    if (lots.isEmpty()) {
      throw new BizException(ErrorCode.AUCTION_NOT_FOUND);
    }
    AuctionLot lot =
        lotId == null
            ? lots.get(0)
            : lotMapper
                .findByIdAndAuction(lotId, auctionId)
                .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_FOUND));
    List<AuctionBid> ownBids = bidMapper.findByAuctionAndSupplier(auctionId, supplierId);
    AuctionBid best =
        ownBids.stream()
            .filter(bid -> Objects.equals(bid.getLotId(), lot.getId()))
            .filter(bid -> bid.getStatus() != AuctionBidStatus.DISQUALIFIED)
            .min(java.util.Comparator.comparing(AuctionBid::getAmount))
            .orElse(null);
    LocalDateTime now = eventMapper.currentTime();
    boolean sealed = Boolean.TRUE.equals(event.getSealed());
    boolean finished = event.getStatus() == AuctionStatus.CLOSED || event.getStatus() == AuctionStatus.AWARDED;
    long remainingMs =
        finished ? 0 : Math.max(0, Duration.between(now, event.getEndAt()).toMillis());
    Integer rank = null;
    Long participants = null;
    if (!sealed) {
      rank =
          best == null
              ? null
              : (int) (bidMapper.countLowerSuppliers(lot.getId(), best.getAmount()) + 1);
      participants = bidMapper.countDistinctSuppliers(lot.getId());
    }
    BigDecimal lowest = sealed ? null : lot.getCurrentLowestAmount();
    BigDecimal nextMin =
        sealed
            ? null
            : lowest == null
                ? lot.getStartPrice()
                : lowest.subtract(lot.getMinDecrement());
    return new PortalAuctionStateView(
        event.getStatus(),
        now,
        event.getEndAt(),
        remainingMs,
        sealed,
        rank,
        participants,
        lowest,
        nextMin,
        best == null ? null : best.getAmount(),
        event.getExtensionCount() == null ? 0 : event.getExtensionCount(),
        event.getExtensionCount() != null && event.getExtensionCount() > 0);
  }

  /** 关闭后的结果：只展示自己的排名/成交价，不暴露中标供应商身份。 */
  @Transactional(readOnly = true)
  public PortalAuctionResultView result(long supplierId, long auctionId) {
    AuctionEvent event = requireInvited(supplierId, auctionId);
    if (event.getStatus() != AuctionStatus.CLOSED && event.getStatus() != AuctionStatus.AWARDED) {
      throw new BizException(ErrorCode.AUCTION_STATUS_INVALID);
    }
    if ("NONE".equalsIgnoreCase(event.getResultVisibility())) {
      return new PortalAuctionResultView(
          event.getStatus(), Boolean.TRUE.equals(event.getSealed()), eventMapper.currentTime(), List.of());
    }
    List<AuctionBid> ownBids = bidMapper.findByAuctionAndSupplier(auctionId, supplierId);
    List<PortalAuctionResultView.Result> results =
        lotMapper.findByAuctionId(auctionId).stream()
            .map(
                lot -> {
                  AuctionBid best =
                      ownBids.stream()
                          .filter(bid -> Objects.equals(bid.getLotId(), lot.getId()))
                          .filter(bid -> bid.getStatus() != AuctionBidStatus.DISQUALIFIED)
                          .min(java.util.Comparator.comparing(AuctionBid::getAmount))
                          .orElse(null);
                  Integer rank =
                      best == null
                          ? null
                          : (int) (bidMapper.countLowerSuppliers(lot.getId(), best.getAmount()) + 1);
                  AuctionBid winningBid =
                      lot.getCurrentLowestBidId() == null
                          ? null
                          : bidMapper.selectById(lot.getCurrentLowestBidId());
                  boolean winner =
                      best != null
                          && winningBid != null
                          && winningBid.getSupplierId() == supplierId
                          && lot.getCurrentLowestAmount() != null
                          && best.getAmount().compareTo(lot.getCurrentLowestAmount()) == 0;
                  return new PortalAuctionResultView.Result(
                      lot.getId(),
                      rank,
                      best == null ? null : best.getAmount(),
                      lot.getCurrentLowestAmount(),
                      winner);
                })
            .toList();
    return new PortalAuctionResultView(
        event.getStatus(),
        Boolean.TRUE.equals(event.getSealed()),
        eventMapper.currentTime(),
        results);
  }

  @Transactional
  public PortalBidResult bid(
      long portalUserId,
      long supplierId,
      String loginName,
      long auctionId,
      long lotId,
      BigDecimal amount,
      String idempotencyKey) {
    rateLimiter.check(portalUserId, auctionId);
    PortalBidResult result =
        bidService.submit(
            new com.spe.common.security.SupplierPortalPrincipal(portalUserId, supplierId, loginName),
            auctionId,
            lotId,
            amount,
            idempotencyKey,
            null);
    broadcastAfterCommit(auctionId, lotId);
    return result;
  }

  /**
   * 出价事务提交后再推送：提交前发送会在回滚（乐观锁冲突等）时把从未落库的状态推给供应商。
   *
   * <p>afterCommit 中事务资源仍绑定但已完成提交；这里的 state() 是只读查询，自调用不经过代理，
   * 每条语句各自读取已提交数据，足以生成推送快照。
   */
  private void broadcastAfterCommit(long auctionId, long lotId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      broadcast(auctionId, lotId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            broadcast(auctionId, lotId);
          }
        });
  }

  private void broadcast(long auctionId, long lotId) {
    try {
      for (long streamSupplierId : streamService.suppliers(auctionId)) {
        try {
          streamService.send(auctionId, streamSupplierId, state(streamSupplierId, auctionId, lotId));
        } catch (RuntimeException ex) {
          // 单个连接/供应商异常不能影响出价结果
          log.debug("竞价状态推送失败：auction={} supplier={}", auctionId, streamSupplierId, ex);
        }
      }
    } catch (RuntimeException ex) {
      log.warn("竞价状态广播失败：auction={} lot={}", auctionId, lotId, ex);
    }
  }

  @Transactional(readOnly = true)
  public PortalStreamTokenView streamToken(long supplierId, long auctionId) {
    requireInvited(supplierId, auctionId);
    AuctionStreamTokenService.Issued issued = streamTokenService.issue(auctionId, supplierId);
    return new PortalStreamTokenView(issued.token(), issued.expiresAt());
  }

  /** 公开 SSE 入口：先消费一次性短令牌，再校验邀请关系。 */
  @Transactional(readOnly = true)
  public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
      long auctionId, String token) {
    long supplierId = streamTokenService.consume(auctionId, token);
    requireInvited(supplierId, auctionId);
    var emitter = streamService.subscribe(auctionId, supplierId);
    try {
      emitter.send(
          org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
              .name("state")
              .data(state(supplierId, auctionId, null)));
    } catch (Exception ex) {
      emitter.completeWithError(ex);
    }
    return emitter;
  }

  private AuctionEvent requireInvited(long supplierId, long auctionId) {
    requireSupplierEnabled(supplierId);
    AuctionEvent event =
        eventMapper
            .findById(auctionId)
            .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_FOUND));
    boolean invited =
        invitationMapper
            .findByAuctionAndSupplier(auctionId, supplierId)
            .filter(
                invitation ->
                    invitation.getStatus() == AuctionInvitationStatus.INVITED
                        || invitation.getStatus() == AuctionInvitationStatus.ACCEPTED)
            .isPresent();
    if (!invited) {
      // 不泄露“存在但未邀请”的事实
      throw new BizException(ErrorCode.AUCTION_NOT_FOUND);
    }
    return event;
  }

  private void requireSupplierEnabled(long supplierId) {
    SupplierBrief supplier = supplierQueryFacade.findById(supplierId).orElse(null);
    if (supplier == null || !supplier.enabled()) {
      throw new BizException(ErrorCode.AUCTION_NOT_INVITED);
    }
  }
}

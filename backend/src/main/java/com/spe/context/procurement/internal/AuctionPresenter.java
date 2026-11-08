package com.spe.context.procurement.internal;

import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 竞价视图装配。 */
@Component
@RequiredArgsConstructor
public class AuctionPresenter {

  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuctionBidMapper bidMapper;

  @Transactional(readOnly = true)
  public AuctionDetailView detail(
      AuctionEvent event, List<AuctionLot> lots, List<AuctionInvitation> invitations) {
    Map<Long, ItemBrief> items = itemsOf(lots);
    return new AuctionDetailView(
        event.getId(),
        event.getAuctionNo(),
        event.getTitle(),
        event.getStatus(),
        Boolean.TRUE.equals(event.getSealed()),
        event.getCurrency(),
        event.getStartAt(),
        event.getEndAt(),
        event.getAntiSnipingSeconds(),
        event.getMaxExtensions(),
        event.getExtensionCount(),
        event.getVersion(),
        lots.stream().map(lot -> detailLot(lot, items)).toList(),
        invitations.stream().map(AuctionInvitation::getSupplierId).toList());
  }

  @Transactional(readOnly = true)
  public PortalAuctionView portalDetail(
      AuctionEvent event,
      List<AuctionLot> lots,
      long supplierId,
      List<AuctionBid> ownBids) {
    Map<Long, ItemBrief> items = itemsOf(lots);
    Map<Long, AuctionBid> lastBidByLot =
        ownBids.stream()
            .collect(
                Collectors.toMap(
                    AuctionBid::getLotId,
                    Function.identity(),
                    (left, right) -> left.getId() >= right.getId() ? left : right));
    boolean sealed = Boolean.TRUE.equals(event.getSealed());
    return new PortalAuctionView(
        event.getId(),
        event.getAuctionNo(),
        event.getTitle(),
        event.getStatus(),
        sealed,
        event.getCurrency(),
        event.getStartAt(),
        event.getEndAt(),
        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
        event.getAntiSnipingSeconds(),
        event.getMaxExtensions(),
        event.getExtensionCount(),
        lots.stream()
            .map(
                lot -> {
                  ItemBrief item = items.get(lot.getItemId());
                  AuctionBid own = lastBidByLot.get(lot.getId());
                  Integer rank =
                      !sealed && own != null
                          ? (int) (bidMapper.countLowerSuppliers(lot.getId(), own.getAmount()) + 1)
                          : null;
                  return new PortalAuctionLotView(
                      lot.getId(),
                      lot.getLineNo(),
                      item == null ? null : item.code(),
                      item == null ? null : item.name(),
                      lot.getQty(),
                      lot.getUom(),
                      lot.getStartPrice(),
                      lot.getMinDecrement(),
                      sealed ? null : lot.getCurrentLowestAmount(),
                      own == null ? null : own.getAmount(),
                      rank);
                })
            .toList());
  }

  @Transactional(readOnly = true)
  public List<PortalAuctionSummaryView> summaries(List<AuctionEvent> events, long supplierId) {
    return events.stream()
        .map(
            event ->
                new PortalAuctionSummaryView(
                    event.getId(),
                    event.getAuctionNo(),
                    event.getTitle(),
                    event.getStatus(),
                    Boolean.TRUE.equals(event.getSealed()),
                    event.getCurrency(),
                    event.getStartAt(),
                    event.getEndAt(),
                    bidMapper.countByAuctionAndSupplier(event.getId(), supplierId)))
        .toList();
  }

  public List<PortalBidView> bids(List<AuctionBid> bids) {
    return bids.stream()
        .map(
            bid ->
                new PortalBidView(
                    bid.getId(),
                    bid.getLotId(),
                    bid.getAmount(),
                    bid.getStatus(),
                    bid.getSubmittedAt()))
        .toList();
  }

  private Map<Long, ItemBrief> itemsOf(List<AuctionLot> lots) {
    return masterDataQueryFacade.findItems(lots.stream().map(AuctionLot::getItemId).toList()).stream()
        .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
  }

  private static AuctionLotDetailView detailLot(AuctionLot lot, Map<Long, ItemBrief> items) {
    ItemBrief item = items.get(lot.getItemId());
    return new AuctionLotDetailView(
        lot.getId(),
        lot.getLineNo(),
        lot.getItemId(),
        item == null ? null : item.code(),
        item == null ? null : item.name(),
        lot.getQty(),
        lot.getUom(),
        lot.getStartPrice(),
        lot.getReservePrice(),
        lot.getMinDecrement(),
        lot.getCurrentLowestAmount(),
        lot.getBidCount());
  }
}

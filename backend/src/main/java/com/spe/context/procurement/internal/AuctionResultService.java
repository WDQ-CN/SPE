package com.spe.context.procurement.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 竞价结果与转 PO（docs/erp/55 §4.4、56 §6.4）。 */
@Service
@RequiredArgsConstructor
public class AuctionResultService {

  private final AuctionResultMapper resultMapper;
  private final AuctionEventMapper eventMapper;
  private final AuctionLotMapper lotMapper;
  private final AuctionBidMapper bidMapper;
  private final PurchaseOrderService purchaseOrderService;
  private final PurchaseOrderMapper purchaseOrderMapper;
  private final SupplierQueryFacade supplierQueryFacade;
  private final AuditFacade auditFacade;

  /** 关闭后按每个 lot 的当前最低有效出价生成结果；重复调用返回已有结果。 */
  @Transactional
  public List<AuctionResultView> award(long auctionId, long operatorId) {
    AuctionEvent event =
        eventMapper
            .findByIdForUpdate(auctionId)
            .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_FOUND));
    if (event.getStatus() == AuctionStatus.AWARDED) {
      return list(auctionId);
    }
    if (event.getStatus() != AuctionStatus.CLOSED) {
      throw new BizException(ErrorCode.AUCTION_STATUS_INVALID);
    }
    int created = 0;
    for (AuctionLot lot : lotMapper.findByAuctionId(auctionId)) {
      if (lot.getCurrentLowestBidId() == null) {
        continue;
      }
      AuctionBid bid = bidMapper.selectById(lot.getCurrentLowestBidId());
      if (bid == null || bid.getStatus() == AuctionBidStatus.DISQUALIFIED) {
        continue;
      }
      AuctionResult result = new AuctionResult();
      result.setAuctionId(auctionId);
      result.setLotId(lot.getId());
      result.setSupplierId(bid.getSupplierId());
      result.setBidId(bid.getId());
      result.setAmount(bid.getAmount());
      result.setStatus(AuctionResultStatus.DRAFT);
      result.setVersion(0);
      result.setCreatedBy(operatorId);
      resultMapper.insert(result);
      created++;
    }
    if (created == 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "没有有效出价，无法生成竞价结果");
    }
    if (eventMapper.updateStatus(
            auctionId, AuctionStatus.AWARDED, AuctionStatus.CLOSED, event.getVersion())
        == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.AUCTION_AWARD,
        AuditTargetType.AUCTION_RESULT,
        auctionId,
        Map.of("auctionNo", event.getAuctionNo(), "resultCount", created));
    return list(auctionId);
  }

  @Transactional
  public AuctionResultView approve(long id, long operatorId) {
    AuctionResult result = lock(id);
    if (result.getStatus() != AuctionResultStatus.DRAFT) {
      throw new BizException(ErrorCode.AUCTION_RESULT_STATUS_INVALID);
    }
    if (result.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    if (resultMapper.markApproved(id, operatorId, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.AUCTION_RESULT_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.AUCTION_RESULT_APPROVE,
        AuditTargetType.AUCTION_RESULT,
        id,
        Map.of("auctionId", result.getAuctionId(), "amount", result.getAmount()));
    return view(resultMapper.findById(id).orElseThrow());
  }

  @Transactional
  public PurchaseOrderView toPurchaseOrder(long id, long operatorId, long plantId) {
    AuctionResult result = lock(id);
    if (result.getStatus() == AuctionResultStatus.PO_CREATED) {
      throw new BizException(ErrorCode.AWARD_ALREADY_CONVERTED);
    }
    if (result.getStatus() != AuctionResultStatus.APPROVED) {
      throw new BizException(ErrorCode.AUCTION_RESULT_STATUS_INVALID);
    }
    AuctionEvent event =
        eventMapper
            .findById(result.getAuctionId())
            .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_FOUND));
    AuctionLot lot =
        lotMapper
            .findByIdAndAuction(result.getLotId(), result.getAuctionId())
            .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_FOUND));
    PurchaseOrderView po =
        purchaseOrderService.create(
            operatorId,
            event.getCompanyId(),
            result.getSupplierId(),
            plantId,
            event.getCurrency(),
            null,
            List.of(
                new PurchaseOrderLineCommand(
                    lot.getItemId(), lot.getQty(), lot.getUom(), result.getAmount())));
    purchaseOrderMapper.linkAuctionResult(po.id(), result.getAuctionId(), result.getId());
    resultMapper.markPoCreated(id);
    auditFacade.record(
        AuditAction.AUCTION_RESULT_TO_PO,
        AuditTargetType.AUCTION_RESULT,
        id,
        Map.of(
            "auctionId", result.getAuctionId(),
            "poId", po.id(),
            "poNo", po.docNo(),
            "amount", result.getAmount()));
    return po;
  }

  @Transactional(readOnly = true)
  public List<AuctionResultView> list(long auctionId) {
    List<AuctionResult> results = resultMapper.findByAuctionId(auctionId);
    Map<Long, SupplierBrief> suppliers =
        supplierQueryFacade
            .findByIds(results.stream().map(AuctionResult::getSupplierId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
    return results.stream()
        .map(result -> view(result, suppliers.get(result.getSupplierId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public AuctionResultView detail(long id) {
    return view(
        resultMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "竞价结果不存在")));
  }

  private AuctionResultView view(AuctionResult result) {
    SupplierBrief supplier = supplierQueryFacade.findById(result.getSupplierId()).orElse(null);
    return view(result, supplier);
  }

  private static AuctionResultView view(AuctionResult result, SupplierBrief supplier) {
    return new AuctionResultView(
        result.getId(),
        result.getAuctionId(),
        result.getLotId(),
        result.getSupplierId(),
        supplier == null ? null : supplier.name(),
        result.getBidId(),
        result.getAmount(),
        result.getStatus(),
        result.getApprovedBy(),
        result.getApprovedAt(),
        result.getVersion(),
        result.getCreatedAt());
  }

  private AuctionResult lock(long id) {
    return resultMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "竞价结果不存在"));
  }
}

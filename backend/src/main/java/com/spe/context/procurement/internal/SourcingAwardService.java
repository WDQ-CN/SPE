package com.spe.context.procurement.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 定标与转采购订单（docs/erp/55 §3.3/§4.3）。 */
@Service
@RequiredArgsConstructor
public class SourcingAwardService {

  private final SourcingAwardMapper awardMapper;
  private final SourcingAwardLineMapper awardLineMapper;
  private final SourcingRfqMapper rfqMapper;
  private final SourcingRfqLineMapper rfqLineMapper;
  private final SupplierQuoteMapper quoteMapper;
  private final SupplierQuoteLineMapper quoteLineMapper;
  private final PurchaseOrderService purchaseOrderService;
  private final PurchaseOrderMapper purchaseOrderMapper;
  private final PurchaseOrderLineMapper purchaseOrderLineMapper;
  private final AuditFacade auditFacade;

  @Transactional
  public SourcingAwardView create(
      long operatorId,
      long rfqId,
      long supplierId,
      String reason,
      List<AwardLineCommand> commands) {
    SourcingRfq rfq = lockRfq(rfqId);
    if (rfq.getStatus() != SourcingRfqStatus.CLOSED
        && rfq.getStatus() != SourcingRfqStatus.EVALUATING
        && !(rfq.getStatus() == SourcingRfqStatus.AWARDED
            && rfq.getAwardPolicy() != AwardPolicy.WHOLE)) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    SupplierQuote quote =
        quoteMapper
            .findByRfqAndSupplier(rfqId, supplierId)
            .filter(value -> value.getStatus() == SupplierQuoteStatus.SUBMITTED)
            .orElseThrow(() -> new BizException(ErrorCode.RFQ_STATUS_INVALID, "供应商没有有效报价"));
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "定标至少需要一行");
    }
    Map<Long, SourcingRfqLine> rfqLines =
        rfqLineMapper.findByRfqId(rfqId).stream()
            .collect(Collectors.toMap(SourcingRfqLine::getId, Function.identity()));
    Map<Long, SupplierQuoteLine> quoteLines =
        quoteLineMapper.findByQuoteId(quote.getId()).stream()
            .collect(Collectors.toMap(SupplierQuoteLine::getId, Function.identity()));
    if (rfq.getAwardPolicy() == AwardPolicy.WHOLE && commands.size() != rfqLines.size()) {
      throw new BizException(ErrorCode.AWARD_QTY_EXCEEDS_QUOTE, "整单定标必须覆盖全部 RFQ 行");
    }

    Set<Long> seen = new HashSet<>();
    List<SourcingAwardLine> awardLines = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (AwardLineCommand command : commands) {
      if (!seen.add(command.rfqLineId())) {
        throw new BizException(ErrorCode.BAD_REQUEST, "同一 RFQ 行不能重复定标");
      }
      SourcingRfqLine rfqLine = rfqLines.get(command.rfqLineId());
      SupplierQuoteLine quoteLine = quoteLines.get(command.quoteLineId());
      if (rfqLine == null || quoteLine == null
          || !quoteLine.getRfqLineId().equals(rfqLine.getId())) {
        throw new BizException(ErrorCode.AWARD_QTY_EXCEEDS_QUOTE, "定标行与报价行不匹配");
      }
      BigDecimal awardedQty = command.awardedQty();
      if (awardedQty == null || awardedQty.signum() <= 0) {
        throw new BizException(ErrorCode.BAD_REQUEST, "定标数量必须大于 0");
      }
      BigDecimal available =
          quoteLine.getQtyAvailable() == null ? rfqLine.getQty() : quoteLine.getQtyAvailable();
      BigDecimal already = awardLineMapper.sumAwardedByRfqLine(rfqLine.getId());
      BigDecimal remaining = rfqLine.getQty().subtract(already);
      if (awardedQty.compareTo(available.min(remaining)) > 0) {
        throw new BizException(ErrorCode.AWARD_QTY_EXCEEDS_QUOTE);
      }
      if (rfq.getAwardPolicy() == AwardPolicy.WHOLE
          && awardedQty.compareTo(rfqLine.getQty()) != 0) {
        throw new BizException(ErrorCode.AWARD_QTY_EXCEEDS_QUOTE, "整单定标数量必须等于 RFQ 数量");
      }
      BigDecimal unitPrice = quoteLine.getUnitPrice();
      BigDecimal amount =
          awardedQty.multiply(unitPrice).setScale(4, RoundingMode.HALF_UP);
      SourcingAwardLine line = new SourcingAwardLine();
      line.setRfqLineId(rfqLine.getId());
      line.setQuoteLineId(quoteLine.getId());
      line.setAwardedQty(awardedQty.setScale(6, RoundingMode.HALF_UP));
      line.setUnitPrice(unitPrice);
      line.setAmount(amount);
      awardLines.add(line);
      total = total.add(amount);
    }

    SourcingAward award = new SourcingAward();
    award.setAwardNo(P2pSupport.docNo("AW"));
    award.setRfqId(rfqId);
    award.setSupplierId(supplierId);
    award.setQuoteId(quote.getId());
    award.setAwardAmount(total);
    award.setCurrency(rfq.getCurrency());
    award.setStatus(SourcingAwardStatus.DRAFT);
    award.setReason(reason);
    award.setVersion(0);
    award.setCreatedBy(operatorId);
    awardMapper.insert(award);
    for (SourcingAwardLine line : awardLines) {
      line.setAwardId(award.getId());
      awardLineMapper.insert(line);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("awardNo", award.getAwardNo());
    detail.put("rfqId", rfqId);
    detail.put("supplierId", supplierId);
    detail.put("lineCount", awardLines.size());
    detail.put("amount", total);
    auditFacade.record(
        AuditAction.AWARD_CREATE, AuditTargetType.SOURCING_AWARD, award.getId(), detail);
    return view(award);
  }

  @Transactional
  public SourcingAwardView approve(long id, long operatorId) {
    SourcingAward award = lock(id);
    if (award.getStatus() != SourcingAwardStatus.DRAFT) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    if (award.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    if (awardMapper.markApproved(id, operatorId, LocalDateTime.now(java.time.ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    rfqMapper.markAwarded(award.getRfqId());
    auditFacade.record(
        AuditAction.AWARD_APPROVE,
        AuditTargetType.SOURCING_AWARD,
        id,
        Map.of("awardNo", award.getAwardNo(), "amount", award.getAwardAmount()));
    return view(awardMapper.findById(id).orElseThrow());
  }

  @Transactional
  public PurchaseOrderView toPurchaseOrder(long id, long operatorId) {
    SourcingAward award = lock(id);
    if (award.getStatus() == SourcingAwardStatus.PO_CREATED
        || award.getStatus() == SourcingAwardStatus.CONTRACT_CREATED) {
      throw new BizException(ErrorCode.AWARD_ALREADY_CONVERTED);
    }
    if (award.getStatus() != SourcingAwardStatus.APPROVED) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    SourcingRfq rfq =
        rfqMapper
            .findById(award.getRfqId())
            .orElseThrow(() -> new BizException(ErrorCode.RFQ_STATUS_INVALID));
    Map<Long, SourcingRfqLine> rfqLines =
        rfqLineMapper.findByRfqId(award.getRfqId()).stream()
            .collect(Collectors.toMap(SourcingRfqLine::getId, Function.identity()));
    List<SourcingAwardLine> awardLines = awardLineMapper.findByAwardId(id);
    List<PurchaseOrderLineCommand> commands =
        awardLines.stream()
            .map(
                line -> {
                  SourcingRfqLine rfqLine = rfqLines.get(line.getRfqLineId());
                  if (rfqLine == null) {
                    throw new BizException(ErrorCode.AWARD_QTY_EXCEEDS_QUOTE);
                  }
                  return new PurchaseOrderLineCommand(
                      rfqLine.getItemId(), line.getAwardedQty(), rfqLine.getUom(), line.getUnitPrice());
                })
            .toList();
    PurchaseOrderView po =
        purchaseOrderService.create(
            operatorId,
            rfq.getCompanyId(),
            award.getSupplierId(),
            rfq.getPlantId(),
            award.getCurrency(),
            null,
            commands);
    purchaseOrderMapper.linkSourcingSource(po.id(), award.getRfqId(), award.getId());
    List<PurchaseOrderLineView> poLines =
        po.lines().stream().sorted(Comparator.comparing(PurchaseOrderLineView::lineNo)).toList();
    List<SourcingAwardLine> sortedAwardLines =
        awardLines.stream().sorted(Comparator.comparing(SourcingAwardLine::getId)).toList();
    for (int i = 0; i < poLines.size() && i < sortedAwardLines.size(); i++) {
      purchaseOrderLineMapper.linkAwardLine(
          poLines.get(i).id(), po.id(), sortedAwardLines.get(i).getId());
    }
    awardMapper.markPoCreated(id);
    auditFacade.record(
        AuditAction.AWARD_TO_PO,
        AuditTargetType.SOURCING_AWARD,
        id,
        Map.of("awardNo", award.getAwardNo(), "poId", po.id(), "poNo", po.docNo()));
    return po;
  }

  @Transactional(readOnly = true)
  public SourcingAwardView detail(long id) {
    return view(
        awardMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "定标单不存在")));
  }

  @Transactional(readOnly = true)
  public List<SourcingAwardView> listByRfq(long rfqId) {
    return awardMapper.findByRfqId(rfqId).stream().map(this::view).toList();
  }

  private SourcingAwardView view(SourcingAward award) {
    return new SourcingAwardView(
        award.getId(),
        award.getAwardNo(),
        award.getRfqId(),
        award.getSupplierId(),
        award.getQuoteId(),
        award.getAwardAmount(),
        award.getCurrency(),
        award.getStatus(),
        award.getReason(),
        award.getApprovedBy(),
        award.getApprovedAt(),
        award.getVersion(),
        award.getCreatedAt(),
        awardLineMapper.findByAwardId(award.getId()).stream()
            .map(
                line ->
                    new SourcingAwardView.Line(
                        line.getId(),
                        line.getRfqLineId(),
                        line.getQuoteLineId(),
                        line.getAwardedQty(),
                        line.getUnitPrice(),
                        line.getAmount()))
            .toList());
  }

  private SourcingRfq lockRfq(long id) {
    return rfqMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.RFQ_STATUS_INVALID, "RFQ 不存在"));
  }

  private SourcingAward lock(long id) {
    return awardMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "定标单不存在"));
  }

  /** 定标行命令。 */
  public record AwardLineCommand(
      long rfqLineId, long quoteLineId, BigDecimal awardedQty) {}
}

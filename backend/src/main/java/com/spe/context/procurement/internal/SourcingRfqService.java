package com.spe.context.procurement.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.PlantBrief;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RFQ、供应商报价与比价（docs/erp/55 §3.1/§3.2/§4.2）。 */
@Service
@RequiredArgsConstructor
public class SourcingRfqService {

  private final SourcingRfqMapper rfqMapper;
  private final SourcingRfqLineMapper lineMapper;
  private final SourcingInvitationMapper invitationMapper;
  private final SupplierQuoteMapper quoteMapper;
  private final SupplierQuoteLineMapper quoteLineMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final RequisitionService requisitionService;
  private final AuditFacade auditFacade;

  @Transactional
  public SourcingRfqView create(
      long operatorId,
      long companyId,
      long plantId,
      String title,
      String currency,
      LocalDateTime deadlineAt,
      Boolean sealed,
      Long sourcePrId,
      String awardPolicy,
      List<RfqLineCommand> lines) {
    CompanyBrief company = requisitionService.requireActiveCompany(companyId);
    String normalizedCurrency = requisitionService.resolveCurrency(company, currency);
    PlantBrief plant =
        masterDataQueryFacade
            .findPlant(plantId)
            .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    if (plant.companyId() != companyId || !plant.active()) {
      throw new BizException(ErrorCode.PLANT_NOT_FOUND);
    }
    if (title == null || title.isBlank()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "RFQ 标题不能为空");
    }
    if (deadlineAt == null || !deadlineAt.isAfter(now())) {
      throw new BizException(ErrorCode.BAD_REQUEST, "报价截止时间必须晚于当前时间");
    }
    if (lines == null || lines.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "RFQ 至少需要一行");
    }
    if (sourcePrId != null) {
      var requisition = requisitionService.load(sourcePrId);
      if (requisition.getStatus()
          != com.spe.context.procurement.api.RequisitionStatus.APPROVED) {
        throw new BizException(ErrorCode.PURCHASE_REQUISITION_SOURCE_INVALID);
      }
    }
    AwardPolicy policy = parsePolicy(awardPolicy);

    SourcingRfq rfq = new SourcingRfq();
    rfq.setRfqNo(P2pSupport.docNo("RFQ"));
    rfq.setCompanyId(companyId);
    rfq.setPlantId(plantId);
    rfq.setBuyerId(operatorId);
    rfq.setTitle(title.trim());
    rfq.setCurrency(normalizedCurrency);
    rfq.setDeadlineAt(deadlineAt);
    rfq.setSealed(Boolean.TRUE.equals(sealed));
    rfq.setStatus(SourcingRfqStatus.DRAFT);
    rfq.setSourcePrId(sourcePrId);
    rfq.setAwardPolicy(policy);
    rfq.setVersion(0);
    rfq.setCreatedBy(operatorId);
    rfqMapper.insert(rfq);

    int lineNo = 1;
    for (RfqLineCommand command : lines) {
      lineMapper.insert(buildLine(rfq.getId(), lineNo++, command));
    }
    auditFacade.record(
        AuditAction.RFQ_CREATE,
        AuditTargetType.SOURCING_RFQ,
        rfq.getId(),
        detail(rfq, lines.size()));
    return detail(rfq.getId());
  }

  @Transactional
  public SourcingRfqView invite(long id, List<Long> supplierIds) {
    SourcingRfq rfq = lock(id);
    if (rfq.getStatus() != SourcingRfqStatus.DRAFT) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    if (supplierIds == null || supplierIds.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "至少需要邀请一家供应商");
    }
    Set<Long> unique = new HashSet<>(supplierIds);
    for (Long supplierId : unique) {
      if (supplierId == null || supplierId <= 0) {
        throw new BizException(ErrorCode.BAD_REQUEST, "供应商 id 不合法");
      }
      SupplierBrief supplier =
          supplierQueryFacade
              .findById(supplierId)
              .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_SUPPLIER_INVALID));
      if (!supplier.enabled()) {
        throw new BizException(ErrorCode.PURCHASE_ORDER_SUPPLIER_INVALID);
      }
      if (invitationMapper.findByRfqAndSupplier(id, supplierId).isPresent()) {
        continue;
      }
      SourcingInvitation invitation = new SourcingInvitation();
      invitation.setRfqId(id);
      invitation.setSupplierId(supplierId);
      invitation.setStatus(InvitationStatus.INVITED);
      invitationMapper.insert(invitation);
    }
    auditFacade.record(
        AuditAction.RFQ_INVITE,
        AuditTargetType.SOURCING_RFQ,
        id,
        Map.of("supplierCount", unique.size()));
    return detail(id);
  }

  @Transactional
  public SourcingRfqView send(long id) {
    SourcingRfq rfq = lock(id);
    if (rfq.getStatus() != SourcingRfqStatus.DRAFT) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    if (lineMapper.findByRfqId(id).isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "RFQ 没有行");
    }
    if (invitationMapper.findByRfqId(id).isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "RFQ 没有邀请供应商");
    }
    if (rfqMapper.markSent(id, now()) == 0) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.RFQ_SEND,
        AuditTargetType.SOURCING_RFQ,
        id,
        detail(rfq, lineMapper.findByRfqId(id).size()));
    return detail(id);
  }

  @Transactional
  public SourcingRfqView close(long id) {
    SourcingRfq rfq = lock(id);
    if (rfq.getStatus() != SourcingRfqStatus.SENT) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    if (rfqMapper.markClosed(id, now()) == 0) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.RFQ_CLOSE,
        AuditTargetType.SOURCING_RFQ,
        id,
        detail(rfq, lineMapper.findByRfqId(id).size()));
    return detail(id);
  }

  @Transactional
  public SourcingRfqView cancel(long id) {
    SourcingRfq rfq = lock(id);
    if (rfqMapper.markCanceled(id) == 0) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    return detail(id);
  }

  @Transactional(readOnly = true)
  public PageResult<SourcingRfq> page(
      int page, int size, Long companyId, String status, String keyword) {
    PageQuery query = PageQuery.of(page, size);
    SourcingRfqStatus parsed = P2pSupport.parse(SourcingRfqStatus.class, status, "RFQ 状态");
    String like = P2pSupport.keyword(keyword);
    return PageResult.of(
        rfqMapper.findPage(companyId, parsed, like, query.offset(), query.size()),
        rfqMapper.countBy(companyId, parsed, like),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public SourcingRfqView detail(long id) {
    SourcingRfq rfq = load(id);
    List<SourcingRfqLine> lines = lineMapper.findByRfqId(id);
    Map<Long, ItemBrief> items =
        masterDataQueryFacade
            .findItems(lines.stream().map(SourcingRfqLine::getItemId).toList())
            .stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    List<SourcingInvitation> invitations = invitationMapper.findByRfqId(id);
    Map<Long, SupplierBrief> suppliers =
        supplierQueryFacade
            .findByIds(invitations.stream().map(SourcingInvitation::getSupplierId).toList())
            .stream()
            .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
    return new SourcingRfqView(
        rfq.getId(),
        rfq.getRfqNo(),
        rfq.getCompanyId(),
        rfq.getPlantId(),
        rfq.getBuyerId(),
        rfq.getTitle(),
        rfq.getCurrency(),
        rfq.getDeadlineAt(),
        Boolean.TRUE.equals(rfq.getSealed()),
        rfq.getStatus(),
        rfq.getSourcePrId(),
        rfq.getAwardPolicy(),
        rfq.getVersion(),
        rfq.getCreatedAt(),
        rfq.getSentAt(),
        rfq.getClosedAt(),
        lines.stream()
            .map(
                line -> {
                  ItemBrief item = items.get(line.getItemId());
                  return new SourcingRfqLineView(
                      line.getId(),
                      line.getLineNo(),
                      line.getItemId(),
                      item == null ? null : item.code(),
                      item == null ? null : item.name(),
                      line.getQty(),
                      line.getUom(),
                      line.getNeedDate(),
                      line.getTargetPrice(),
                      line.getSpec());
                })
            .toList(),
        invitations.stream()
            .map(
                invitation -> {
                  SupplierBrief supplier = suppliers.get(invitation.getSupplierId());
                  return new SourcingInvitationView(
                      invitation.getId(),
                      invitation.getSupplierId(),
                      supplier == null ? null : supplier.code(),
                      supplier == null ? null : supplier.name(),
                      invitation.getStatus(),
                      invitation.getInvitedAt(),
                      invitation.getRespondedAt());
                })
            .toList());
  }

  /** 内部查看报价：密封 RFQ 截止前拒绝，避免采购员提前泄密。 */
  @Transactional(readOnly = true)
  public List<SupplierQuoteView> quotes(long rfqId) {
    SourcingRfq rfq = load(rfqId);
    requireQuotesVisible(rfq);
    return quoteViews(quoteMapper.findByRfqId(rfqId), rfq);
  }

  /** 比价矩阵：按含税总成本标出每行最优。 */
  @Transactional(readOnly = true)
  public SourcingComparisonView comparison(long rfqId) {
    SourcingRfq rfq = load(rfqId);
    requireQuotesVisible(rfq);
    List<SourcingRfqLine> rfqLines = lineMapper.findByRfqId(rfqId);
    List<SupplierQuote> quotes = quoteMapper.findByRfqId(rfqId);
    Map<Long, SupplierBrief> suppliers =
        supplierQueryFacade
            .findByIds(quotes.stream().map(SupplierQuote::getSupplierId).toList())
            .stream()
            .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
    Map<Long, List<SupplierQuoteLine>> quoteLines =
        quotes.stream()
            .collect(
                Collectors.toMap(
                    SupplierQuote::getId, quote -> quoteLineMapper.findByQuoteId(quote.getId())));
    List<SourcingComparisonView.Line> lines = new ArrayList<>();
    for (SourcingRfqLine rfqLine : rfqLines) {
      List<SourcingComparisonView.Cell> cells = new ArrayList<>();
      for (SupplierQuote quote : quotes) {
        SupplierQuoteLine quoteLine =
            quoteLines.getOrDefault(quote.getId(), List.of()).stream()
                .filter(line -> line.getRfqLineId().equals(rfqLine.getId()))
                .findFirst()
                .orElse(null);
        if (quoteLine == null) {
          continue;
        }
        SupplierBrief supplier = suppliers.get(quote.getSupplierId());
        BigDecimal taxIncluded =
            quoteLine.getUnitPrice()
                .multiply(BigDecimal.ONE.add(quoteLine.getTaxRate()))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalCost =
            taxIncluded.multiply(rfqLine.getQty()).setScale(4, RoundingMode.HALF_UP);
        boolean canSupply =
            quoteLine.getQtyAvailable() == null
                || quoteLine.getQtyAvailable().compareTo(rfqLine.getQty()) >= 0;
        cells.add(
            new SourcingComparisonView.Cell(
                quote.getId(),
                quoteLine.getId(),
                quote.getSupplierId(),
                supplier == null ? null : supplier.code(),
                supplier == null ? null : supplier.name(),
                quoteLine.getUnitPrice(),
                quoteLine.getTaxRate(),
                taxIncluded,
                quoteLine.getLeadTimeDays(),
                quoteLine.getMoq(),
                quoteLine.getQtyAvailable(),
                totalCost,
                canSupply));
      }
      Long bestSupplierId = null;
      BigDecimal bestCost = null;
      for (SourcingComparisonView.Cell cell : cells) {
        if (!cell.best()) {
          continue;
        }
        if (bestCost == null || cell.totalCost().compareTo(bestCost) < 0) {
          bestCost = cell.totalCost();
          bestSupplierId = cell.supplierId();
        }
      }
      final Long bestId = bestSupplierId;
      final BigDecimal best = bestCost;
      List<SourcingComparisonView.Cell> marked =
          cells.stream()
              .map(
                  cell ->
                      new SourcingComparisonView.Cell(
                          cell.quoteId(),
                          cell.quoteLineId(),
                          cell.supplierId(),
                          cell.supplierCode(),
                          cell.supplierName(),
                          cell.unitPrice(),
                          cell.taxRate(),
                          cell.taxIncludedPrice(),
                          cell.leadTimeDays(),
                          cell.moq(),
                          cell.qtyAvailable(),
                          cell.totalCost(),
                          best != null
                              && cell.totalCost().compareTo(best) == 0
                              && Objects.equals(cell.supplierId(), bestId)))
              .sorted(Comparator.comparing(SourcingComparisonView.Cell::totalCost))
              .toList();
      lines.add(
          new SourcingComparisonView.Line(
              rfqLine.getId(), rfqLine.getItemId(), rfqLine.getQty(), bestSupplierId, marked));
    }
    return new SourcingComparisonView(rfqId, true, lines);
  }

  /** 供应商门户提交/更新报价；截止前最后一次提交生效。 */
  @Transactional
  public SupplierQuoteView submitQuote(
      long supplierId,
      long portalUserId,
      long rfqId,
      LocalDate validUntil,
      List<QuoteLineCommand> commands) {
    SourcingRfq rfq = lock(rfqId);
    SourcingInvitation invitation =
        invitationMapper
            .findByRfqAndSupplier(rfqId, supplierId)
            .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_INVITED));
    if (rfq.getStatus() == SourcingRfqStatus.DRAFT) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    if (rfq.getStatus() == SourcingRfqStatus.SENT
        && now().isAfter(rfq.getDeadlineAt())) {
      rfqMapper.markClosed(rfqId, now());
      throw new BizException(ErrorCode.AUCTION_CLOSED);
    }
    if (rfq.getStatus() != SourcingRfqStatus.SENT) {
      throw new BizException(ErrorCode.AUCTION_CLOSED);
    }
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "报价至少需要一行");
    }
    Map<Long, SourcingRfqLine> rfqLines =
        lineMapper.findByRfqId(rfqId).stream()
            .collect(Collectors.toMap(SourcingRfqLine::getId, Function.identity()));
    SupplierQuote existing = quoteMapper.findByRfqAndSupplier(rfqId, supplierId).orElse(null);
    if (existing != null
        && (existing.getStatus() == SupplierQuoteStatus.ACCEPTED
            || existing.getStatus() == SupplierQuoteStatus.REJECTED)) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    SupplierQuote quote = existing == null ? new SupplierQuote() : existing;
    if (existing == null) {
      quote.setQuoteNo(P2pSupport.docNo("QT"));
      quote.setRfqId(rfqId);
      quote.setSupplierId(supplierId);
      quote.setCurrency(rfq.getCurrency());
      quote.setValidUntil(validUntil);
      quote.setStatus(SupplierQuoteStatus.SUBMITTED);
      quote.setSubmittedAt(now());
      quote.setVersion(0);
      quote.setCreatedBy(portalUserId);
      quoteMapper.insert(quote);
    } else {
      quoteMapper.markSubmitted(quote.getId(), rfq.getCurrency(), validUntil);
      quoteLineMapper.deleteByQuoteId(quote.getId());
    }
    for (QuoteLineCommand command : commands) {
      SourcingRfqLine rfqLine = rfqLines.get(command.rfqLineId());
      if (rfqLine == null) {
        throw new BizException(ErrorCode.BAD_REQUEST, "报价行不属于当前 RFQ");
      }
      SupplierQuoteLine line = buildQuoteLine(quote.getId(), rfqLine, command);
      quoteLineMapper.insert(line);
    }
    invitationMapper.markQuoted(invitation.getId());
    auditFacade.record(
        AuditAction.QUOTE_SUBMIT,
        AuditTargetType.SUPPLIER_QUOTE,
        quote.getId(),
        Map.of("rfqId", rfqId, "supplierId", supplierId, "lineCount", commands.size()));
    return quoteView(quoteMapper.findById(quote.getId()).orElseThrow(), rfq);
  }

  @Transactional
  public SupplierQuoteView withdrawQuote(long supplierId, long rfqId) {
    SourcingRfq rfq = lock(rfqId);
    SupplierQuote quote =
        quoteMapper
            .findByRfqAndSupplier(rfqId, supplierId)
            .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "报价不存在"));
    if (now().isAfter(rfq.getDeadlineAt())) {
      throw new BizException(ErrorCode.AUCTION_CLOSED);
    }
    if (quoteMapper.markWithdrawn(quote.getId()) == 0) {
      throw new BizException(ErrorCode.RFQ_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.QUOTE_WITHDRAW,
        AuditTargetType.SUPPLIER_QUOTE,
        quote.getId(),
        Map.of("rfqId", rfqId, "supplierId", supplierId));
    return quoteView(quote, rfq);
  }

  @Transactional(readOnly = true)
  public List<SupplierQuoteView> portalQuotes(long supplierId, long rfqId) {
    SourcingRfq rfq = load(rfqId);
    return quoteMapper.findByRfqAndSupplier(rfqId, supplierId).stream()
        .map(quote -> quoteView(quote, rfq))
        .toList();
  }

  @Transactional(readOnly = true)
  public PageResult<PortalRfqSummaryView> portalPage(
      long supplierId, int page, int size, String status) {
    PageQuery query = PageQuery.of(page, size);
    SourcingRfqStatus parsed = P2pSupport.parse(SourcingRfqStatus.class, status, "RFQ 状态");
    List<SourcingInvitation> invitations = invitationMapper.findBySupplier(supplierId);
    List<Long> rfqIds = invitations.stream().map(SourcingInvitation::getRfqId).toList();
    Map<Long, SourcingRfq> rfqs =
        rfqIds.isEmpty()
            ? Map.of()
            : rfqMapper.findByIds(rfqIds).stream()
                .collect(Collectors.toMap(SourcingRfq::getId, Function.identity()));
    Map<Long, InvitationStatus> invitationStatus =
        invitations.stream()
            .collect(
                Collectors.toMap(
                    SourcingInvitation::getRfqId, SourcingInvitation::getStatus, (a, b) -> a));
    List<PortalRfqSummaryView> rows =
        invitations.stream()
            .map(invitation -> rfqs.get(invitation.getRfqId()))
            .filter(Objects::nonNull)
            .filter(rfq -> rfq.getStatus() != SourcingRfqStatus.DRAFT)
            .filter(rfq -> rfq.getStatus() != SourcingRfqStatus.CANCELED)
            .filter(rfq -> parsed == null || rfq.getStatus() == parsed)
            .sorted(Comparator.comparing(SourcingRfq::getId).reversed())
            .map(
                rfq -> {
                  SupplierQuote quote =
                      quoteMapper.findByRfqAndSupplier(rfq.getId(), supplierId).orElse(null);
                  return new PortalRfqSummaryView(
                      rfq.getId(),
                      rfq.getRfqNo(),
                      rfq.getTitle(),
                      rfq.getCurrency(),
                      rfq.getDeadlineAt(),
                      rfq.getStatus(),
                      invitationStatus.get(rfq.getId()),
                      quote == null ? null : quote.getStatus());
                })
            .toList();
    int from = (int) Math.min(query.offset(), rows.size());
    int to = Math.min(from + query.size(), rows.size());
    return PageResult.of(rows.subList(from, to), rows.size(), query.page(), query.size());
  }

  @Transactional
  public PortalRfqView portalDetail(long supplierId, long rfqId) {
    SourcingRfq rfq = load(rfqId);
    SourcingInvitation invitation =
        invitationMapper
            .findByRfqAndSupplier(rfqId, supplierId)
            .orElseThrow(() -> new BizException(ErrorCode.AUCTION_NOT_INVITED));
    invitationMapper.markViewed(invitation.getId());
    SupplierQuote quote =
        quoteMapper.findByRfqAndSupplier(rfqId, supplierId).orElse(null);
    return new PortalRfqView(
        rfq.getId(),
        rfq.getRfqNo(),
        rfq.getTitle(),
        rfq.getCurrency(),
        rfq.getDeadlineAt(),
        Boolean.TRUE.equals(rfq.getSealed()),
        rfq.getStatus(),
        invitation.getStatus(),
        lineViews(rfqId),
        quote == null ? null : quoteView(quote, rfq));
  }

  private SourcingRfqLine buildLine(long rfqId, int lineNo, RfqLineCommand command) {
    if (command.qty() == null || command.qty().signum() <= 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "RFQ 行数量必须大于 0");
    }
    ItemBrief item =
        masterDataQueryFacade
            .findItem(command.itemId())
            .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
    if (!item.active()) {
      throw new BizException(ErrorCode.ITEM_NOT_FOUND);
    }
    SourcingRfqLine line = new SourcingRfqLine();
    line.setRfqId(rfqId);
    line.setLineNo(lineNo);
    line.setItemId(command.itemId());
    line.setQty(command.qty().setScale(6, RoundingMode.HALF_UP));
    line.setUom(command.uom());
    line.setNeedDate(command.needDate());
    line.setTargetPrice(command.targetPrice());
    line.setSpec(command.spec());
    return line;
  }

  private SupplierQuoteLine buildQuoteLine(
      long quoteId, SourcingRfqLine rfqLine, QuoteLineCommand command) {
    if (command.unitPrice() == null || command.unitPrice().signum() < 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "报价单价不能为负");
    }
    BigDecimal taxRate =
        command.taxRate() == null ? BigDecimal.ZERO : command.taxRate();
    if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) > 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "税率必须在 0~1 之间");
    }
    if (command.leadTimeDays() != null && command.leadTimeDays() < 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "交期天数不能为负");
    }
    if (command.moq() != null && command.moq().signum() < 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "MOQ 不能为负");
    }
    if (command.qtyAvailable() != null && command.qtyAvailable().signum() < 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "可供数量不能为负");
    }
    SupplierQuoteLine line = new SupplierQuoteLine();
    line.setQuoteId(quoteId);
    line.setRfqLineId(rfqLine.getId());
    line.setUnitPrice(command.unitPrice().setScale(4, RoundingMode.HALF_UP));
    line.setTaxRate(taxRate);
    line.setTaxAmount(
        command
            .unitPrice()
            .multiply(rfqLine.getQty())
            .multiply(taxRate)
            .setScale(4, RoundingMode.HALF_UP));
    line.setLeadTimeDays(command.leadTimeDays());
    line.setMoq(command.moq());
    line.setQtyAvailable(command.qtyAvailable());
    line.setIncoterm(command.incoterm());
    line.setRemark(command.remark());
    return line;
  }

  private List<SupplierQuoteView> quoteViews(List<SupplierQuote> quotes, SourcingRfq rfq) {
    if (quotes.isEmpty()) {
      return List.of();
    }
    Map<Long, SupplierBrief> suppliers =
        supplierQueryFacade
            .findByIds(quotes.stream().map(SupplierQuote::getSupplierId).toList())
            .stream()
            .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
    return quotes.stream()
        .map(
            quote -> {
              SupplierBrief supplier = suppliers.get(quote.getSupplierId());
              return new SupplierQuoteView(
                  quote.getId(),
                  quote.getQuoteNo(),
                  quote.getRfqId(),
                  quote.getSupplierId(),
                  supplier == null ? null : supplier.code(),
                  supplier == null ? null : supplier.name(),
                  quote.getCurrency(),
                  quote.getValidUntil(),
                  quote.getStatus(),
                  quote.getSubmittedAt(),
                  quote.getVersion(),
                  quoteLineMapper.findByQuoteId(quote.getId()).stream()
                      .map(
                          line ->
                              new SupplierQuoteLineView(
                                  line.getId(),
                                  line.getRfqLineId(),
                                  line.getUnitPrice(),
                                  line.getTaxRate(),
                                  line.getTaxAmount(),
                                  line.getLeadTimeDays(),
                                  line.getMoq(),
                                  line.getQtyAvailable(),
                                  line.getIncoterm(),
                                  line.getRemark()))
                      .toList());
            })
        .toList();
  }

  private SupplierQuoteView quoteView(SupplierQuote quote, SourcingRfq rfq) {
    SupplierBrief supplier = supplierQueryFacade.findById(quote.getSupplierId()).orElse(null);
    return new SupplierQuoteView(
        quote.getId(),
        quote.getQuoteNo(),
        quote.getRfqId(),
        quote.getSupplierId(),
        supplier == null ? null : supplier.code(),
        supplier == null ? null : supplier.name(),
        quote.getCurrency(),
        quote.getValidUntil(),
        quote.getStatus(),
        quote.getSubmittedAt(),
        quote.getVersion(),
        quoteLineMapper.findByQuoteId(quote.getId()).stream()
            .map(
                line ->
                    new SupplierQuoteLineView(
                        line.getId(),
                        line.getRfqLineId(),
                        line.getUnitPrice(),
                        line.getTaxRate(),
                        line.getTaxAmount(),
                        line.getLeadTimeDays(),
                        line.getMoq(),
                        line.getQtyAvailable(),
                        line.getIncoterm(),
                        line.getRemark()))
            .toList());
  }

  private List<SourcingRfqLineView> lineViews(long rfqId) {
    List<SourcingRfqLine> lines = lineMapper.findByRfqId(rfqId);
    Map<Long, ItemBrief> items =
        masterDataQueryFacade
            .findItems(lines.stream().map(SourcingRfqLine::getItemId).toList())
            .stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    return lines.stream()
        .map(
            line -> {
              ItemBrief item = items.get(line.getItemId());
              return new SourcingRfqLineView(
                  line.getId(),
                  line.getLineNo(),
                  line.getItemId(),
                  item == null ? null : item.code(),
                  item == null ? null : item.name(),
                  line.getQty(),
                  line.getUom(),
                  line.getNeedDate(),
                  line.getTargetPrice(),
                  line.getSpec());
            })
        .toList();
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(java.time.ZoneOffset.UTC);
  }

  private SourcingRfq load(long id) {
    return rfqMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.RFQ_STATUS_INVALID, "RFQ 不存在"));
  }

  private SourcingRfq lock(long id) {
    return rfqMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.RFQ_STATUS_INVALID, "RFQ 不存在"));
  }

  private static void requireQuotesVisible(SourcingRfq rfq) {
    if (Boolean.TRUE.equals(rfq.getSealed())
        && (rfq.getStatus() == SourcingRfqStatus.DRAFT
            || rfq.getStatus() == SourcingRfqStatus.SENT)) {
      throw new BizException(ErrorCode.QUOTE_SEALED);
    }
  }

  private static AwardPolicy parsePolicy(String policy) {
    if (policy == null || policy.isBlank()) {
      return AwardPolicy.WHOLE;
    }
    try {
      return AwardPolicy.valueOf(policy.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "定标策略不合法：" + policy);
    }
  }

  private static Map<String, Object> detail(SourcingRfq rfq, int lineCount) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("rfqNo", rfq.getRfqNo());
    detail.put("title", rfq.getTitle());
    detail.put("lineCount", lineCount);
    detail.put("sealed", rfq.getSealed());
    return detail;
  }

  /** RFQ 行创建命令。 */
  public record RfqLineCommand(
      long itemId,
      BigDecimal qty,
      String uom,
      LocalDate needDate,
      BigDecimal targetPrice,
      String spec) {}

  /** 门户报价行命令。 */
  public record QuoteLineCommand(
      long rfqLineId,
      BigDecimal unitPrice,
      BigDecimal taxRate,
      Integer leadTimeDays,
      BigDecimal moq,
      BigDecimal qtyAvailable,
      String incoterm,
      String remark) {}
}

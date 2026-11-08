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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

/** 合同/框架协议 + 供应商价格库（docs/erp/55 §3.5/§4.5）。 */
@Service
@RequiredArgsConstructor
public class PurchaseContractService {

  private final PurchaseContractMapper contractMapper;
  private final PurchaseContractLineMapper lineMapper;
  private final ContractConsumptionMapper consumptionMapper;
  private final SupplierPriceMapper supplierPriceMapper;
  private final PurchaseOrderService purchaseOrderService;
  private final PurchaseOrderMapper purchaseOrderMapper;
  private final PurchaseOrderLineMapper purchaseOrderLineMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public PurchaseContractView create(
      long operatorId,
      long companyId,
      long supplierId,
      String title,
      String currency,
      LocalDate validFrom,
      LocalDate validTo,
      String paymentTerms,
      BigDecimal totalLimitAmount,
      BigDecimal totalLimitQty,
      List<ContractLineCommand> lines) {
    CompanyBrief company =
        masterDataQueryFacade
            .findCompany(companyId)
            .filter(CompanyBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    String normalizedCurrency = P2pSupport.upper(currency);
    if (normalizedCurrency == null) {
      normalizedCurrency = company.baseCurrency();
    }
    if (!company.baseCurrency().equals(normalizedCurrency)) {
      throw new BizException(ErrorCode.PROCUREMENT_CURRENCY_INVALID);
    }
    SupplierBrief supplier =
        supplierQueryFacade
            .findById(supplierId)
            .filter(SupplierBrief::enabled)
            .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_SUPPLIER_INVALID));
    if (title == null || title.isBlank()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "合同标题不能为空");
    }
    if (validFrom == null || validTo == null || validTo.isBefore(validFrom)) {
      throw new BizException(ErrorCode.CONTRACT_LIMIT_EXCEEDED, "合同有效期不合法");
    }
    if (lines == null || lines.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "合同至少需要一行");
    }
    Set<String> keys = new HashSet<>();
    PurchaseContract contract = new PurchaseContract();
    contract.setContractNo(P2pSupport.docNo("CT"));
    contract.setCompanyId(companyId);
    contract.setSupplierId(supplierId);
    contract.setTitle(title.trim());
    contract.setCurrency(normalizedCurrency);
    contract.setValidFrom(validFrom);
    contract.setValidTo(validTo);
    contract.setPaymentTerms(P2pSupport.optional(paymentTerms, 32, "付款条件"));
    contract.setStatus(PurchaseContractStatus.DRAFT);
    contract.setTotalLimitAmount(totalLimitAmount);
    contract.setTotalLimitQty(totalLimitQty);
    contract.setVersion(0);
    contract.setCreatedBy(operatorId);
    contractMapper.insert(contract);

    int lineNo = 1;
    for (ContractLineCommand command : lines) {
      ItemBrief item =
          masterDataQueryFacade
              .findItem(command.itemId())
              .filter(ItemBrief::active)
              .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
      String uom = command.uom() == null ? item.baseUom() : P2pSupport.upper(command.uom());
      if (!keys.add(item.id() + "|" + uom)) {
        throw new BizException(ErrorCode.BAD_REQUEST, "同一物料与单位不能重复");
      }
      if (command.price() == null || command.price().signum() < 0) {
        throw new BizException(ErrorCode.BAD_REQUEST, "合同单价不能为负");
      }
      PurchaseContractLine line = new PurchaseContractLine();
      line.setContractId(contract.getId());
      line.setLineNo(lineNo++);
      line.setItemId(item.id());
      line.setUom(uom);
      line.setPrice(command.price().setScale(4, RoundingMode.HALF_UP));
      line.setMinQty(command.minQty());
      line.setMaxQty(command.maxQty());
      line.setTaxIncluded(Boolean.TRUE.equals(command.taxIncluded()));
      line.setValidFrom(command.validFrom());
      line.setValidTo(command.validTo());
      lineMapper.insert(line);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("contractNo", contract.getContractNo());
    detail.put("supplierId", supplier.id());
    detail.put("lineCount", lines.size());
    auditFacade.record(
        AuditAction.CONTRACT_CREATE,
        AuditTargetType.PURCHASE_CONTRACT,
        contract.getId(),
        detail);
    return detail(contract.getId());
  }

  @Transactional
  public PurchaseContractView activate(long id, long operatorId) {
    PurchaseContract contract = lock(id);
    if (contract.getStatus() != PurchaseContractStatus.DRAFT) {
      throw new BizException(ErrorCode.CONTRACT_STATUS_INVALID);
    }
    if (contract.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    if (contractMapper.markActive(id, operatorId, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.CONTRACT_STATUS_INVALID);
    }
    for (PurchaseContractLine line : lineMapper.findByContractId(id)) {
      supplierPriceMapper.upsert(
          contract.getCompanyId(),
          contract.getSupplierId(),
          line.getItemId(),
          line.getUom(),
          line.getMinQty() == null ? BigDecimal.ZERO : line.getMinQty(),
          line.getPrice(),
          Boolean.TRUE.equals(line.getTaxIncluded()),
          contract.getCurrency(),
          line.getValidFrom() == null ? contract.getValidFrom() : line.getValidFrom(),
          line.getValidTo() == null ? contract.getValidTo() : line.getValidTo(),
          SupplierPriceSource.CONTRACT,
          contract.getId(),
          null);
    }
    auditFacade.record(
        AuditAction.CONTRACT_ACTIVATE,
        AuditTargetType.PURCHASE_CONTRACT,
        id,
        Map.of("contractNo", contract.getContractNo()));
    return detail(id);
  }

  @Transactional
  public PurchaseContractView terminate(long id) {
    PurchaseContract contract = lock(id);
    if (contract.getStatus() != PurchaseContractStatus.ACTIVE) {
      throw new BizException(ErrorCode.CONTRACT_STATUS_INVALID);
    }
    contractMapper.markTerminated(id);
    supplierPriceMapper.deactivateByContract(id);
    auditFacade.record(
        AuditAction.CONTRACT_TERMINATE,
        AuditTargetType.PURCHASE_CONTRACT,
        id,
        Map.of("contractNo", contract.getContractNo()));
    return detail(id);
  }

  @Transactional
  public PurchaseOrderView releaseOrder(
      long id, long operatorId, long plantId, List<ReleaseLineCommand> commands) {
    PurchaseContract contract = lock(id);
    if (contract.getStatus() != PurchaseContractStatus.ACTIVE) {
      throw new BizException(ErrorCode.CONTRACT_STATUS_INVALID);
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    if (today.isBefore(contract.getValidFrom()) || today.isAfter(contract.getValidTo())) {
      throw new BizException(ErrorCode.CONTRACT_LIMIT_EXCEEDED, "合同不在有效期内");
    }
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "释放订单至少需要一行");
    }
    Map<Long, PurchaseContractLine> lines =
        lineMapper.findByContractId(id).stream()
            .collect(Collectors.toMap(PurchaseContractLine::getId, Function.identity()));
    Set<Long> seen = new HashSet<>();
    BigDecimal totalQty = BigDecimal.ZERO;
    BigDecimal totalAmount = BigDecimal.ZERO;
    List<PurchaseOrderLineCommand> poCommands = new java.util.ArrayList<>();
    List<PurchaseContractLine> orderedLines = new java.util.ArrayList<>();
    for (ReleaseLineCommand command : commands) {
      PurchaseContractLine line = lines.get(command.contractLineId());
      if (line == null || !seen.add(line.getId())) {
        throw new BizException(ErrorCode.BAD_REQUEST, "合同行不存在或重复释放");
      }
      if (command.qty() == null || command.qty().signum() <= 0) {
        throw new BizException(ErrorCode.BAD_REQUEST, "释放数量必须大于 0");
      }
      if (line.getMinQty() != null && command.qty().compareTo(line.getMinQty()) < 0) {
        throw new BizException(ErrorCode.CONTRACT_LIMIT_EXCEEDED, "释放数量低于合同行最小释放量");
      }
      if (line.getValidFrom() != null && today.isBefore(line.getValidFrom())
          || line.getValidTo() != null && today.isAfter(line.getValidTo())) {
        throw new BizException(ErrorCode.CONTRACT_LIMIT_EXCEEDED, "合同行不在有效期内");
      }
      BigDecimal consumed = consumptionMapper.sumQtyByLine(line.getId());
      if (line.getMaxQty() != null
          && consumed.add(command.qty()).compareTo(line.getMaxQty()) > 0) {
        throw new BizException(ErrorCode.CONTRACT_LIMIT_EXCEEDED, "合同行数量超限");
      }
      BigDecimal amount =
          command.qty().multiply(line.getPrice()).setScale(4, RoundingMode.HALF_UP);
      totalQty = totalQty.add(command.qty());
      totalAmount = totalAmount.add(amount);
      poCommands.add(
          new PurchaseOrderLineCommand(line.getItemId(), command.qty(), line.getUom(), line.getPrice()));
      orderedLines.add(line);
    }
    BigDecimal consumedQty = consumptionMapper.sumQtyByContract(id);
    if (contract.getTotalLimitQty() != null
        && consumedQty.add(totalQty).compareTo(contract.getTotalLimitQty()) > 0) {
      throw new BizException(ErrorCode.CONTRACT_LIMIT_EXCEEDED, "合同总数量超限");
    }
    if (contract.getTotalLimitAmount() != null
        && consumptionMapper
                .sumAmountByContract(id)
                .add(totalAmount)
                .compareTo(contract.getTotalLimitAmount())
            > 0) {
      throw new BizException(ErrorCode.CONTRACT_LIMIT_EXCEEDED, "合同总金额超限");
    }

    masterDataQueryFacade
        .findPlant(plantId)
        .filter(plant -> plant.active() && plant.companyId() == contract.getCompanyId())
        .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    PurchaseOrderView po =
        purchaseOrderService.create(
            operatorId,
            contract.getCompanyId(),
            contract.getSupplierId(),
            plantId,
            contract.getCurrency(),
            contract.getPaymentTerms(),
            poCommands);
    purchaseOrderMapper.linkContract(po.id(), contract.getId());
    List<PurchaseOrderLineView> poLines =
        po.lines().stream()
            .sorted(java.util.Comparator.comparing(PurchaseOrderLineView::lineNo))
            .toList();
    for (int i = 0; i < poLines.size() && i < orderedLines.size(); i++) {
      PurchaseOrderLineView poLine = poLines.get(i);
      PurchaseContractLine contractLine = orderedLines.get(i);
      purchaseOrderLineMapper.linkContractLine(
          poLine.id(), po.id(), contractLine.getId());
      ContractConsumption consumption = new ContractConsumption();
      consumption.setContractLineId(contractLine.getId());
      consumption.setPoId(po.id());
      consumption.setPoLineId(poLine.id());
      consumption.setQty(poLine.qty());
      consumption.setAmount(poLine.amount());
      consumptionMapper.insert(consumption);
    }
    auditFacade.record(
        AuditAction.CONTRACT_RELEASE_ORDER,
        AuditTargetType.PURCHASE_CONTRACT,
        id,
        Map.of(
            "contractNo", contract.getContractNo(),
            "poId", po.id(),
            "poNo", po.docNo(),
            "qty", totalQty,
            "amount", totalAmount));
    // linkContract/linkContractLine 会递增版本；返回创建时的快照会让调用方拿到过期 version。
    return purchaseOrderService.detail(po.id());
  }

  @Transactional(readOnly = true)
  public PageResult<PurchaseContract> page(
      int page, int size, Long companyId, Long supplierId, String status) {
    PageQuery query = PageQuery.of(page, size);
    PurchaseContractStatus parsed =
        P2pSupport.parse(PurchaseContractStatus.class, status, "合同状态");
    return PageResult.of(
        contractMapper.findPage(companyId, supplierId, parsed, query.offset(), query.size()),
        contractMapper.countBy(companyId, supplierId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public PurchaseContractView detail(long id) {
    PurchaseContract contract =
        contractMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "合同不存在"));
    SupplierBrief supplier = supplierQueryFacade.findById(contract.getSupplierId()).orElse(null);
    List<PurchaseContractLine> lines = lineMapper.findByContractId(id);
    Map<Long, ItemBrief> items =
        masterDataQueryFacade
            .findItems(lines.stream().map(PurchaseContractLine::getItemId).toList())
            .stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    BigDecimal consumedQty = consumptionMapper.sumQtyByContract(id);
    BigDecimal consumedAmount = consumptionMapper.sumAmountByContract(id);
    return new PurchaseContractView(
        contract.getId(),
        contract.getContractNo(),
        contract.getCompanyId(),
        contract.getSupplierId(),
        supplier == null ? null : supplier.name(),
        contract.getTitle(),
        contract.getCurrency(),
        contract.getValidFrom(),
        contract.getValidTo(),
        contract.getPaymentTerms(),
        contract.getStatus(),
        contract.getTotalLimitAmount(),
        contract.getTotalLimitQty(),
        contract.getSourceAwardId(),
        contract.getVersion(),
        contract.getCreatedAt(),
        consumedQty,
        consumedAmount,
        lines.stream()
            .map(
                line -> {
                  ItemBrief item = items.get(line.getItemId());
                  return new PurchaseContractLineView(
                      line.getId(),
                      line.getLineNo(),
                      line.getItemId(),
                      item == null ? null : item.code(),
                      item == null ? null : item.name(),
                      line.getUom(),
                      line.getPrice(),
                      line.getMinQty(),
                      line.getMaxQty(),
                      Boolean.TRUE.equals(line.getTaxIncluded()),
                      line.getValidFrom(),
                      line.getValidTo(),
                      consumptionMapper.sumQtyByLine(line.getId()));
                })
            .toList());
  }

  @Transactional(readOnly = true)
  public List<ContractConsumptionView> consumption(long id) {
    return consumptionMapper.findByContractId(id).stream()
        .map(
            row ->
                new ContractConsumptionView(
                    row.getId(),
                    row.getContractLineId(),
                    row.getPoId(),
                    row.getPoLineId(),
                    row.getQty(),
                    row.getAmount(),
                    row.getConsumedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<SupplierPriceView> effectivePrices(long supplierId, Long itemId, LocalDate date) {
    LocalDate effectiveDate = date == null ? LocalDate.now(ZoneOffset.UTC) : date;
    List<SupplierPrice> prices =
        supplierPriceMapper.findEffective(supplierId, itemId, effectiveDate);
    SupplierBrief supplier = supplierQueryFacade.findById(supplierId).orElse(null);
    Map<Long, ItemBrief> items =
        masterDataQueryFacade
            .findItems(prices.stream().map(SupplierPrice::getItemId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    return prices.stream()
        .map(price -> priceView(price, supplier, items.get(price.getItemId())))
        .toList();
  }

  @Transactional
  public SupplierPriceView createSupplierPrice(
      long companyId,
      long supplierId,
      long itemId,
      String uom,
      BigDecimal minQty,
      BigDecimal price,
      Boolean taxIncluded,
      String currency,
      LocalDate validFrom,
      LocalDate validTo) {
    CompanyBrief company =
        masterDataQueryFacade
            .findCompany(companyId)
            .filter(CompanyBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    SupplierBrief supplier =
        supplierQueryFacade
            .findById(supplierId)
            .filter(SupplierBrief::enabled)
            .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_SUPPLIER_INVALID));
    ItemBrief item =
        masterDataQueryFacade
            .findItem(itemId)
            .filter(ItemBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
    if (price == null || price.signum() < 0 || validFrom == null) {
      throw new BizException(ErrorCode.SUPPLIER_PRICE_INVALID);
    }
    if (validTo != null && validTo.isBefore(validFrom)) {
      throw new BizException(ErrorCode.SUPPLIER_PRICE_INVALID);
    }
    String normalizedCurrency = currency == null ? company.baseCurrency() : P2pSupport.upper(currency);
    if (!company.baseCurrency().equals(normalizedCurrency)) {
      throw new BizException(ErrorCode.PROCUREMENT_CURRENCY_INVALID);
    }
    String normalizedUom = uom == null ? item.baseUom() : P2pSupport.upper(uom);
    BigDecimal normalizedMinQty = minQty == null ? BigDecimal.ZERO : minQty;
    supplierPriceMapper.upsert(
        companyId,
        supplierId,
        itemId,
        normalizedUom,
        normalizedMinQty,
        price.setScale(4, RoundingMode.HALF_UP),
        Boolean.TRUE.equals(taxIncluded),
        normalizedCurrency,
        validFrom,
        validTo,
        SupplierPriceSource.MANUAL,
        null,
        null);
    SupplierPrice saved =
        supplierPriceMapper
            .findByKey(companyId, supplierId, itemId, normalizedUom, normalizedMinQty, validFrom)
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_PRICE_INVALID));
    auditFacade.record(
        AuditAction.SUPPLIER_PRICE_UPDATE,
        AuditTargetType.SUPPLIER_PRICE,
        saved.getId(),
        Map.of("supplierId", supplierId, "itemId", itemId, "price", price));
    return priceView(saved, supplier, item);
  }

  private SupplierPriceView priceView(
      SupplierPrice price, SupplierBrief supplier, ItemBrief item) {
    return new SupplierPriceView(
        price.getId(),
        price.getCompanyId(),
        price.getSupplierId(),
        supplier == null ? null : supplier.name(),
        price.getItemId(),
        item == null ? null : item.code(),
        item == null ? null : item.name(),
        price.getUom(),
        price.getMinQty(),
        price.getPrice(),
        Boolean.TRUE.equals(price.getTaxIncluded()),
        price.getCurrency(),
        price.getValidFrom(),
        price.getValidTo(),
        price.getSourceType(),
        price.getContractId(),
        price.getAwardId(),
        price.getStatus(),
        price.getVersion());
  }

  private PurchaseContract lock(long id) {
    return contractMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "合同不存在"));
  }

  /** 合同行创建命令。 */
  public record ContractLineCommand(
      long itemId,
      String uom,
      BigDecimal price,
      BigDecimal minQty,
      BigDecimal maxQty,
      Boolean taxIncluded,
      LocalDate validFrom,
      LocalDate validTo) {}

  /** 释放订单行命令。 */
  public record ReleaseLineCommand(long contractLineId, BigDecimal qty) {}
}

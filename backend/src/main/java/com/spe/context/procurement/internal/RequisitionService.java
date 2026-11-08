package com.spe.context.procurement.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.procurement.api.ApprovalDocType;
import com.spe.context.procurement.api.RequisitionStatus;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 请购单（W2；docs/erp/32 §4）。
 *
 * <p>状态守卫：只有 DRAFT 可改、只有申请人可提交、审批人不能是申请人（SoD，4004），审批级别由
 * {@link ApprovalMatrixService} 按金额裁定。所有迁移带 version 守卫，0 行后复读区分 3001 与 2203。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequisitionService {

  private final PurchaseRequisitionMapper requisitionMapper;
  private final PurchaseRequisitionLineMapper lineMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final ApprovalMatrixService approvalMatrixService;
  private final AuditFacade auditFacade;
  private final ProcurementPresenter presenter;

  @Transactional(readOnly = true)
  public PageResult<RequisitionSummaryView> page(
      int page, int size, Long companyId, String status, String keyword) {
    PageQuery query = PageQuery.of(page, size);
    RequisitionStatus parsed = P2pSupport.parse(RequisitionStatus.class, status, "状态");
    String like = P2pSupport.keyword(keyword);
    List<PurchaseRequisition> rows =
        requisitionMapper.findPage(companyId, parsed, like, query.offset(), query.size());
    return PageResult.of(
        presenter.requisitions(rows),
        requisitionMapper.countBy(companyId, parsed, like),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public RequisitionView detail(long id) {
    return presenter.requisition(load(id));
  }

  @Transactional
  public RequisitionView create(
      long operatorId,
      long companyId,
      LocalDate needDate,
      String currency,
      List<RequisitionLineCommand> commands) {
    CompanyBrief company = requireActiveCompany(companyId);
    String normalizedCurrency = resolveCurrency(company, currency);
    Normalized normalized = normalize(commands);

    PurchaseRequisition requisition = new PurchaseRequisition();
    requisition.setDocNo(P2pSupport.docNo("PR"));
    requisition.setCompanyId(companyId);
    requisition.setRequesterId(operatorId);
    requisition.setNeedDate(needDate);
    requisition.setCurrency(normalizedCurrency);
    requisition.setAmount(normalized.total());
    requisition.setStatus(RequisitionStatus.DRAFT);
    requisition.setVersion(0);
    requisitionMapper.insert(requisition);
    insertLines(requisition.getId(), normalized.lines());

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("companyId", companyId);
    detail.put("requesterId", operatorId);
    detail.put("needDate", needDate);
    detail.put("currency", normalizedCurrency);
    detail.put("lineCount", normalized.lines().size());
    detail.put("amount", normalized.total());
    auditFacade.record(
        AuditAction.PURCHASE_REQUISITION_CREATE,
        AuditTargetType.PURCHASE_REQUISITION,
        requisition.getId(),
        detail);
    log.info("创建请购单 {}（id={}，金额={}）", requisition.getDocNo(), requisition.getId(), normalized.total());
    return presenter.requisition(load(requisition.getId()));
  }

  @Transactional
  public RequisitionView update(
      long id,
      long operatorId,
      LocalDate needDate,
      String currency,
      List<RequisitionLineCommand> commands,
      int version) {
    PurchaseRequisition current = load(id);
    requireRequester(current, operatorId);
    requireStatus(current, RequisitionStatus.DRAFT);
    CompanyBrief company = requireActiveCompany(current.getCompanyId());
    String normalizedCurrency = resolveCurrency(company, currency);
    Normalized normalized = normalize(commands);
    if (requisitionMapper.updateDraft(id, needDate, normalizedCurrency, normalized.total(), version)
        == 0) {
      throw conflictOrStatus(id, RequisitionStatus.DRAFT, version);
    }
    lineMapper.deleteByRequisitionId(id);
    insertLines(id, normalized.lines());

    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "needDate", current.getNeedDate(), needDate);
    AuditDetails.putIfChanged(detail, "currency", current.getCurrency(), normalizedCurrency);
    AuditDetails.putIfChanged(detail, "amount", current.getAmount(), normalized.total());
    detail.put("lineCount", normalized.lines().size());
    auditFacade.record(
        AuditAction.PURCHASE_REQUISITION_UPDATE,
        AuditTargetType.PURCHASE_REQUISITION,
        id,
        detail);
    return presenter.requisition(load(id));
  }

  @Transactional
  public RequisitionView submit(long id, long operatorId, int version) {
    PurchaseRequisition current = load(id);
    requireRequester(current, operatorId);
    requireStatus(current, RequisitionStatus.DRAFT);
    // 提交时先确认金额有审批规则：没有规则应在此刻失败，而不是等到审批人点开才发现（2217）。
    P2pApprovalRule rule =
        approvalMatrixService.resolve(ApprovalDocType.PR, current.getCompanyId(), current.getAmount());
    if (requisitionMapper.submit(id, operatorId, version) == 0) {
      throw conflictOrStatus(id, RequisitionStatus.DRAFT, version);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("amount", current.getAmount());
    detail.put("requiredRole", rule.getRequiredRole());
    detail.put("lineCount", lineMapper.findByRequisitionId(id).size());
    auditFacade.record(
        AuditAction.PURCHASE_REQUISITION_SUBMIT,
        AuditTargetType.PURCHASE_REQUISITION,
        id,
        detail);
    return presenter.requisition(load(id));
  }

  @Transactional
  public RequisitionView approve(long id, long operatorId, boolean admin, int version) {
    PurchaseRequisition current = load(id);
    requireStatus(current, RequisitionStatus.SUBMITTED);
    requireNotRequester(current, operatorId);
    P2pApprovalRule rule =
        approvalMatrixService.requireSatisfied(
            ApprovalDocType.PR, current.getCompanyId(), current.getAmount(), admin);
    if (requisitionMapper.approve(id, operatorId, version) == 0) {
      throw conflictOrStatus(id, RequisitionStatus.SUBMITTED, version);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("amount", current.getAmount());
    detail.put("requiredRole", rule.getRequiredRole());
    detail.put("approverRole", admin ? "ADMIN" : "MANAGER");
    auditFacade.record(
        AuditAction.PURCHASE_REQUISITION_APPROVE,
        AuditTargetType.PURCHASE_REQUISITION,
        id,
        detail);
    return presenter.requisition(load(id));
  }

  @Transactional
  public RequisitionView reject(
      long id, long operatorId, boolean admin, String comment, int version) {
    String normalizedComment = P2pSupport.required(comment, 500, "驳回意见");
    PurchaseRequisition current = load(id);
    requireStatus(current, RequisitionStatus.SUBMITTED);
    requireNotRequester(current, operatorId);
    P2pApprovalRule rule =
        approvalMatrixService.requireSatisfied(
            ApprovalDocType.PR, current.getCompanyId(), current.getAmount(), admin);
    if (requisitionMapper.reject(id, operatorId, version) == 0) {
      throw conflictOrStatus(id, RequisitionStatus.SUBMITTED, version);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("amount", current.getAmount());
    detail.put("requiredRole", rule.getRequiredRole());
    detail.put("comment", AuditDetails.commentSummary(normalizedComment));
    auditFacade.record(
        AuditAction.PURCHASE_REQUISITION_REJECT,
        AuditTargetType.PURCHASE_REQUISITION,
        id,
        detail);
    return presenter.requisition(load(id));
  }

  /** PO 转单服务在创建 PO 前调用：状态与版本守卫；0 行由调用方翻成 2212/3001。 */
  @Transactional
  public void markConverted(long id, int version) {
    if (requisitionMapper.convert(id, version) == 0) {
      PurchaseRequisition current = load(id);
      if (current.getVersion() != version) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
      throw new BizException(ErrorCode.PURCHASE_REQUISITION_SOURCE_INVALID);
    }
  }

  public PurchaseRequisition load(long id) {
    return requisitionMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_REQUISITION_NOT_FOUND));
  }

  public CompanyBrief requireActiveCompany(long companyId) {
    CompanyBrief company =
        masterDataQueryFacade
            .findCompany(companyId)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    if (!company.active()) {
      throw new BizException(ErrorCode.MASTER_DATA_HIERARCHY_INVALID);
    }
    return company;
  }

  public String resolveCurrency(CompanyBrief company, String currency) {
    String normalized = P2pSupport.upper(currency);
    if (normalized == null || normalized.isEmpty()) {
      normalized = company.baseCurrency();
    }
    if (!company.baseCurrency().equals(normalized)) {
      throw new BizException(ErrorCode.PROCUREMENT_CURRENCY_INVALID);
    }
    return normalized;
  }

  private Normalized normalize(List<RequisitionLineCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.PURCHASE_REQUISITION_LINE_INVALID, "至少需要一行请购明细");
    }
    Set<String> keys = new HashSet<>();
    List<PurchaseRequisitionLine> lines = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (RequisitionLineCommand command : commands) {
      if (command == null || command.itemId() <= 0) {
        throw new BizException(ErrorCode.PURCHASE_REQUISITION_LINE_INVALID, "物料不能为空");
      }
      ItemBrief item =
          masterDataQueryFacade
              .findItem(command.itemId())
              .orElseThrow(
                  () ->
                      new BizException(
                          ErrorCode.PURCHASE_REQUISITION_LINE_INVALID,
                          "物料不存在：" + command.itemId()));
      if (!item.active()) {
        throw new BizException(
            ErrorCode.PURCHASE_REQUISITION_LINE_INVALID, "物料已停用：" + item.code());
      }
      BigDecimal qty = P2pSupport.qty(command.qty());
      if (qty == null || qty.signum() <= 0) {
        throw new BizException(ErrorCode.PURCHASE_REQUISITION_LINE_INVALID, "数量必须大于 0");
      }
      BigDecimal price = P2pSupport.price(command.estimatedPrice());
      if (price == null || price.signum() < 0) {
        throw new BizException(ErrorCode.PURCHASE_REQUISITION_LINE_INVALID, "预估单价不能为负");
      }
      String uom = P2pSupport.upper(command.uom());
      if (uom == null || uom.isEmpty()) {
        uom = item.baseUom();
      }
      if (!keys.add(item.id() + "|" + uom)) {
        throw new BizException(
            ErrorCode.PURCHASE_REQUISITION_LINE_INVALID, "同一物料与单位不能重复：" + item.code());
      }
      PurchaseRequisitionLine line = new PurchaseRequisitionLine();
      line.setLineNo(lines.size() + 1);
      line.setItemId(item.id());
      line.setQty(qty);
      line.setUom(uom);
      line.setEstimatedPrice(price);
      line.setAmount(P2pSupport.lineAmount(qty, price));
      P2pSupport.ensureAmount(
          line.getAmount(), ErrorCode.PURCHASE_REQUISITION_LINE_INVALID, "行金额");
      total = P2pSupport.sum(total, line.getAmount());
      P2pSupport.ensureAmount(total, ErrorCode.PURCHASE_REQUISITION_LINE_INVALID, "请购金额");
      lines.add(line);
    }
    return new Normalized(lines, total);
  }

  private void insertLines(long requisitionId, List<PurchaseRequisitionLine> lines) {
    for (PurchaseRequisitionLine line : lines) {
      line.setRequisitionId(requisitionId);
      lineMapper.insert(line);
    }
  }

  private void requireRequester(PurchaseRequisition requisition, long operatorId) {
    if (requisition.getRequesterId() != operatorId) {
      throw new BizException(ErrorCode.FORBIDDEN, "只有申请人可以修改或提交该请购单");
    }
  }

  private void requireNotRequester(PurchaseRequisition requisition, long operatorId) {
    if (requisition.getRequesterId() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
  }

  private void requireStatus(PurchaseRequisition requisition, RequisitionStatus expected) {
    if (requisition.getStatus() != expected) {
      throw new BizException(ErrorCode.PURCHASE_REQUISITION_STATUS_INVALID);
    }
  }

  private BizException conflictOrStatus(long id, RequisitionStatus expected, int version) {
    PurchaseRequisition current = load(id);
    if (current.getVersion() != version) {
      return new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    if (current.getStatus() != expected) {
      return new BizException(ErrorCode.PURCHASE_REQUISITION_STATUS_INVALID);
    }
    return new BizException(ErrorCode.CONCURRENT_CONFLICT);
  }

  private record Normalized(List<PurchaseRequisitionLine> lines, BigDecimal total) {}
}

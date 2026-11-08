package com.spe.context.quality.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.quality.api.events.InspectionCompletedEvent;
import com.spe.context.quality.api.events.NcrOpenedEvent;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 检验计划/检验单/结果（docs/erp/37 §4.1）：不合格自动开 NCR。 */
@Service
@RequiredArgsConstructor
public class InspectionService {

  private final InspectionPlanMapper planMapper;
  private final InspectionPlanCharacteristicMapper characteristicMapper;
  private final InspectionOrderMapper orderMapper;
  private final InspectionResultMapper resultMapper;
  private final NcrMapper ncrMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public InspectionPlanView createPlan(
      long operatorId,
      long companyId,
      long itemId,
      Long supplierId,
      String type,
      String version,
      String samplingPlan,
      BigDecimal aql,
      List<CharacteristicCommand> characteristics) {
    masterDataQueryFacade
        .findCompany(companyId)
        .filter(com.spe.context.masterdata.api.CompanyBrief::active)
        .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    ItemBrief item =
        masterDataQueryFacade
            .findItem(itemId)
            .filter(ItemBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
    if (supplierId != null) {
      SupplierBrief supplier =
          supplierQueryFacade
              .findById(supplierId)
              .filter(SupplierBrief::enabled)
              .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_SUPPLIER_INVALID));
    }
    if (characteristics == null || characteristics.isEmpty()) {
      throw new BizException(ErrorCode.INSPECTION_RESULT_INVALID, "检验计划至少需要一个特性");
    }
    InspectionPlan plan = new InspectionPlan();
    plan.setPlanNo(QualitySupport.docNo("QC"));
    plan.setCompanyId(companyId);
    plan.setItemId(item.id());
    plan.setSupplierId(supplierId);
    plan.setInspectionType(parseType(type));
    plan.setVersion(version == null || version.isBlank() ? "V1" : version.trim());
    plan.setStatus(InspectionPlanStatus.DRAFT);
    plan.setSamplingPlan(samplingPlan);
    plan.setAql(aql);
    plan.setCreatedBy(operatorId);
    planMapper.insert(plan);
    int lineNo = 1;
    for (CharacteristicCommand command : characteristics) {
      if (command.name() == null || command.name().isBlank()) {
        throw new BizException(ErrorCode.INSPECTION_RESULT_INVALID, "特性名不能为空");
      }
      InspectionPlanCharacteristic entity = new InspectionPlanCharacteristic();
      entity.setPlanId(plan.getId());
      entity.setLineNo(lineNo++);
      entity.setName(command.name().trim());
      entity.setSpec(command.spec());
      entity.setCritical(Boolean.TRUE.equals(command.critical()));
      entity.setMethod(command.method());
      characteristicMapper.insert(entity);
    }
    auditFacade.record(
        AuditAction.INSPECTION_PLAN_CREATE,
        AuditTargetType.INSPECTION_PLAN,
        plan.getId(),
        Map.of("planNo", plan.getPlanNo(), "itemId", itemId, "type", plan.getInspectionType()));
    return planDetail(plan.getId());
  }

  @Transactional
  public InspectionPlanView activatePlan(long id) {
    InspectionPlan plan =
        planMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.INSPECTION_PLAN_NOT_FOUND));
    if (planMapper.markActive(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.INSPECTION_PLAN_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.INSPECTION_PLAN_ACTIVATE,
        AuditTargetType.INSPECTION_PLAN,
        id,
        Map.of("planNo", plan.getPlanNo()));
    return planDetail(id);
  }

  @Transactional
  public InspectionOrderView createOrder(
      long operatorId,
      long companyId,
      long planId,
      String sourceType,
      Long sourceId,
      Long sourceLineId,
      long itemId,
      Long supplierId,
      String lotNo,
      BigDecimal qty) {
    InspectionPlan plan =
        planMapper
            .findById(planId)
            .filter(p -> p.getStatus() == InspectionPlanStatus.ACTIVE)
            .orElseThrow(() -> new BizException(ErrorCode.INSPECTION_PLAN_STATUS_INVALID));
    if (plan.getCompanyId() != companyId) {
      throw new BizException(ErrorCode.INSPECTION_STATUS_INVALID, "检验计划不属于指定公司");
    }
    if (!plan.getItemId().equals(itemId)) {
      throw new BizException(ErrorCode.INSPECTION_STATUS_INVALID, "检验计划与物料不匹配");
    }
    if (plan.getSupplierId() != null && !plan.getSupplierId().equals(supplierId)) {
      throw new BizException(ErrorCode.INSPECTION_STATUS_INVALID, "检验计划与供应商不匹配");
    }
    if (qty == null || qty.signum() <= 0) {
      throw new BizException(ErrorCode.INSPECTION_STATUS_INVALID, "检验数量必须大于 0");
    }
    InspectionOrder order = new InspectionOrder();
    order.setOrderNo(QualitySupport.docNo("QI"));
    order.setCompanyId(companyId);
    order.setPlanId(planId);
    order.setSourceType(
        sourceType == null || sourceType.isBlank() ? "MANUAL" : sourceType.trim().toUpperCase());
    order.setSourceId(sourceId);
    order.setSourceLineId(sourceLineId);
    order.setItemId(itemId);
    order.setSupplierId(supplierId);
    order.setLotNo(lotNo);
    order.setQty(qty.setScale(6, java.math.RoundingMode.HALF_UP));
    order.setStatus(InspectionStatus.PENDING);
    order.setCreatedBy(operatorId);
    orderMapper.insert(order);
    auditFacade.record(
        AuditAction.INSPECTION_ORDER_CREATE,
        AuditTargetType.INSPECTION_ORDER,
        order.getId(),
        Map.of("orderNo", order.getOrderNo(), "itemId", itemId, "qty", order.getQty()));
    return orderDetail(order.getId());
  }

  @Transactional
  public InspectionOrderView recordResults(long id, List<ResultCommand> commands) {
    InspectionOrder order =
        orderMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.INSPECTION_NOT_FOUND));
    if (order.getStatus() != InspectionStatus.PENDING
        && order.getStatus() != InspectionStatus.IN_PROGRESS) {
      throw new BizException(ErrorCode.INSPECTION_STATUS_INVALID);
    }
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.INSPECTION_RESULT_INVALID, "至少需要一条检验结果");
    }
    boolean failed = false;
    int lineNo = 1;
    for (ResultCommand command : commands) {
      InspectionResultStatus result = parseResult(command.result());
      if (command.name() == null || command.name().isBlank()) {
        throw new BizException(ErrorCode.INSPECTION_RESULT_INVALID, "检验特性名不能为空");
      }
      InspectionResult entity = new InspectionResult();
      entity.setInspectionId(id);
      entity.setLineNo(lineNo++);
      entity.setCharacteristicId(command.characteristicId());
      entity.setName(command.name().trim());
      entity.setMeasuredValue(command.measuredValue());
      entity.setResult(result);
      entity.setRemark(command.remark());
      resultMapper.insert(entity);
      failed |= result == InspectionResultStatus.FAIL;
    }
    InspectionStatus target = failed ? InspectionStatus.FAILED : InspectionStatus.PASSED;
    if (orderMapper.markCompleted(id, target, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.INSPECTION_STATUS_INVALID);
    }
    Long ncrId = null;
    if (failed) {
      ncrId = openNcr(order, commands);
    }
    auditFacade.record(
        AuditAction.INSPECTION_RESULT_RECORD,
        AuditTargetType.INSPECTION_ORDER,
        id,
        Map.of("orderNo", order.getOrderNo(), "result", target, "resultCount", commands.size()));
    eventPublisher.publish(
        new InspectionCompletedEvent(
            id,
            order.getCompanyId(),
            order.getItemId(),
            order.getSupplierId(),
            order.getLotNo(),
            target.name()));
    return orderDetail(id);
  }

  @Transactional(readOnly = true)
  public PageResult<InspectionPlan> pagePlans(int page, int size, long companyId) {
    PageQuery query = PageQuery.of(page, size);
    return PageResult.of(
        planMapper.findPage(companyId, query.offset(), query.size()),
        planMapper.countBy(companyId),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public InspectionPlanView planDetail(long id) {
    InspectionPlan plan =
        planMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.INSPECTION_PLAN_NOT_FOUND));
    ItemBrief item = masterDataQueryFacade.findItem(plan.getItemId()).orElse(null);
    return new InspectionPlanView(
        plan.getId(),
        plan.getPlanNo(),
        plan.getCompanyId(),
        plan.getItemId(),
        item == null ? null : item.code(),
        item == null ? null : item.name(),
        plan.getSupplierId(),
        plan.getInspectionType(),
        plan.getVersion(),
        plan.getStatus(),
        plan.getSamplingPlan(),
        plan.getAql(),
        plan.getCreatedAt(),
        plan.getActivatedAt(),
        characteristicMapper.findByPlanId(id).stream()
            .map(
                characteristic ->
                    new InspectionPlanView.Characteristic(
                        characteristic.getId(),
                        characteristic.getLineNo(),
                        characteristic.getName(),
                        characteristic.getSpec(),
                        Boolean.TRUE.equals(characteristic.getCritical()),
                        characteristic.getMethod()))
            .toList());
  }

  @Transactional(readOnly = true)
  public PageResult<InspectionOrder> pageOrders(
      int page, int size, Long companyId, String status) {
    PageQuery query = PageQuery.of(page, size);
    InspectionStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseStatus(status);
    return PageResult.of(
        orderMapper.findPage(companyId, parsed, query.offset(), query.size()),
        orderMapper.countBy(companyId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public InspectionOrderView orderDetail(long id) {
    InspectionOrder order =
        orderMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.INSPECTION_NOT_FOUND));
    Ncr ncr = ncrMapper.findByInspectionId(id).orElse(null);
    return new InspectionOrderView(
        order.getId(),
        order.getOrderNo(),
        order.getCompanyId(),
        order.getPlanId(),
        order.getSourceType(),
        order.getSourceId(),
        order.getSourceLineId(),
        order.getItemId(),
        order.getSupplierId(),
        order.getLotNo(),
        order.getQty(),
        order.getStatus(),
        order.getCreatedAt(),
        order.getCompletedAt(),
        ncr == null ? null : ncr.getId(),
        resultMapper.findByInspectionId(id).stream()
            .map(
                result ->
                    new InspectionOrderView.Result(
                        result.getId(),
                        result.getLineNo(),
                        result.getCharacteristicId(),
                        result.getName(),
                        result.getMeasuredValue(),
                        result.getResult(),
                        result.getRemark()))
            .toList());
  }

  private Long openNcr(InspectionOrder order, List<ResultCommand> commands) {
    if (ncrMapper.findByInspectionId(order.getId()).isPresent()) {
      return ncrMapper.findByInspectionId(order.getId()).get().getId();
    }
    String defect =
        commands.stream()
            .filter(command -> parseResult(command.result()) == InspectionResultStatus.FAIL)
            .map(ResultCommand::name)
            .distinct()
            .collect(java.util.stream.Collectors.joining("、"));
    Ncr ncr = new Ncr();
    ncr.setNcrNo(QualitySupport.docNo("NCR"));
    ncr.setCompanyId(order.getCompanyId());
    ncr.setSourceType("INSPECTION");
    ncr.setSourceId(order.getId());
    ncr.setInspectionId(order.getId());
    ncr.setItemId(order.getItemId());
    ncr.setSupplierId(order.getSupplierId());
    ncr.setLotNo(order.getLotNo());
    ncr.setQty(order.getQty());
    ncr.setDefect(defect.isBlank() ? "检验不合格" : defect);
    ncr.setStatus(NcrStatus.OPEN);
    ncr.setCreatedBy(order.getCreatedBy());
    ncrMapper.insert(ncr);
    auditFacade.record(
        AuditAction.NCR_CREATE,
        AuditTargetType.NCR,
        ncr.getId(),
        Map.of("ncrNo", ncr.getNcrNo(), "inspectionId", order.getId(), "itemId", order.getItemId()));
    eventPublisher.publish(
        new NcrOpenedEvent(
            ncr.getId(),
            ncr.getCompanyId(),
            ncr.getSupplierId(),
            ncr.getItemId(),
            ncr.getLotNo(),
            ncr.getDefect()));
    return ncr.getId();
  }

  private static InspectionType parseType(String type) {
    if (type == null || type.isBlank()) {
      return InspectionType.IQC;
    }
    try {
      return InspectionType.valueOf(type.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "检验类型不合法：" + type);
    }
  }

  private static InspectionResultStatus parseResult(String result) {
    if (result == null || result.isBlank()) {
      throw new BizException(ErrorCode.INSPECTION_RESULT_INVALID, "检验结果不能为空");
    }
    try {
      return InspectionResultStatus.valueOf(result.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.INSPECTION_RESULT_INVALID, "检验结果只能是 PASS/FAIL");
    }
  }

  private static InspectionStatus parseStatus(String status) {
    try {
      return InspectionStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "检验状态不合法：" + status);
    }
  }

  /** 检验特性命令。 */
  public record CharacteristicCommand(String name, String spec, Boolean critical, String method) {}

  /** 检验结果命令。 */
  public record ResultCommand(
      Long characteristicId, String name, String measuredValue, String result, String remark) {}
}

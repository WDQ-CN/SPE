package com.spe.context.quality.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.quality.api.events.CapaOpenedEvent;
import com.spe.context.quality.api.events.CapaVerifiedEvent;
import com.spe.context.quality.api.events.NcrOpenedEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** NCR 处置与 CAPA（docs/erp/37 §4.3）。 */
@Service
@RequiredArgsConstructor
public class NcrCapaService {

  private final NcrMapper ncrMapper;
  private final CapaMapper capaMapper;
  private final InspectionOrderMapper inspectionMapper;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public NcrView create(
      long operatorId,
      long companyId,
      long itemId,
      Long supplierId,
      String lotNo,
      BigDecimal qty,
      String defect) {
    if (defect == null || defect.isBlank()) {
      throw new BizException(ErrorCode.NCR_STATUS_INVALID, "缺陷描述不能为空");
    }
    Ncr ncr = new Ncr();
    ncr.setNcrNo(QualitySupport.docNo("NCR"));
    ncr.setCompanyId(companyId);
    ncr.setSourceType("MANUAL");
    ncr.setItemId(itemId);
    ncr.setSupplierId(supplierId);
    ncr.setLotNo(lotNo);
    ncr.setQty(qty == null ? BigDecimal.ZERO : qty);
    ncr.setDefect(defect.trim());
    ncr.setStatus(NcrStatus.OPEN);
    ncr.setCreatedBy(operatorId);
    ncrMapper.insert(ncr);
    auditFacade.record(
        AuditAction.NCR_CREATE,
        AuditTargetType.NCR,
        ncr.getId(),
        Map.of("ncrNo", ncr.getNcrNo(), "itemId", itemId, "defect", ncr.getDefect()));
    eventPublisher.publish(
        new NcrOpenedEvent(
            ncr.getId(), companyId, supplierId, itemId, lotNo, ncr.getDefect()));
    return view(ncr);
  }

  @Transactional
  public NcrView dispose(long id, String disposition, String reason) {
    Ncr ncr =
        ncrMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.NCR_NOT_FOUND));
    if (ncr.getStatus() != NcrStatus.OPEN) {
      throw new BizException(ErrorCode.NCR_STATUS_INVALID);
    }
    if (reason == null || reason.isBlank()) {
      throw new BizException(ErrorCode.NCR_STATUS_INVALID, "处置原因不能为空");
    }
    NcrDisposition parsed = parseDisposition(disposition);
    if (ncrMapper.markDispositioned(id, parsed, reason.trim(), LocalDateTime.now(ZoneOffset.UTC))
        == 0) {
      throw new BizException(ErrorCode.NCR_STATUS_INVALID);
    }
    if (parsed == NcrDisposition.DEVIATE && ncr.getInspectionId() != null
        && inspectionMapper.markDeviated(ncr.getInspectionId()) == 0) {
      throw new BizException(ErrorCode.NCR_STATUS_INVALID, "检验单状态不允许偏离处置");
    }
    auditFacade.record(
        AuditAction.NCR_DISPOSITION,
        AuditTargetType.NCR,
        id,
        Map.of("ncrNo", ncr.getNcrNo(), "disposition", parsed, "reason", reason));
    return view(ncrMapper.findById(id).orElseThrow());
  }

  @Transactional
  public NcrView close(long id) {
    Ncr ncr =
        ncrMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.NCR_NOT_FOUND));
    if (ncrMapper.markClosed(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.NCR_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.NCR_CLOSE,
        AuditTargetType.NCR,
        id,
        Map.of("ncrNo", ncr.getNcrNo()));
    return view(ncrMapper.findById(id).orElseThrow());
  }

  @Transactional
  public CapaView createCapa(
      long operatorId, long ncrId, String rootCause, String actionPlan) {
    Ncr ncr =
        ncrMapper
            .findById(ncrId)
            .orElseThrow(() -> new BizException(ErrorCode.NCR_NOT_FOUND));
    if (actionPlan == null || actionPlan.isBlank()) {
      throw new BizException(ErrorCode.CAPA_STATUS_INVALID, "CAPA 措施计划不能为空");
    }
    Capa capa = new Capa();
    capa.setCapaNo(QualitySupport.docNo("CAPA"));
    capa.setCompanyId(ncr.getCompanyId());
    capa.setNcrId(ncrId);
    capa.setSupplierId(ncr.getSupplierId());
    capa.setRootCause(rootCause);
    capa.setActionPlan(actionPlan);
    capa.setStatus(CapaStatus.OPEN);
    capa.setCreatedBy(operatorId);
    capaMapper.insert(capa);
    auditFacade.record(
        AuditAction.CAPA_CREATE,
        AuditTargetType.CAPA,
        capa.getId(),
        Map.of("capaNo", capa.getCapaNo(), "ncrId", ncrId));
    eventPublisher.publish(
        new CapaOpenedEvent(capa.getId(), capa.getCompanyId(), capa.getSupplierId()));
    return view(capa);
  }

  @Transactional
  public CapaView verifyCapa(long id, long operatorId) {
    Capa capa =
        capaMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.CAPA_NOT_FOUND));
    if (capaMapper.markVerified(id, operatorId, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.CAPA_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.CAPA_VERIFY,
        AuditTargetType.CAPA,
        id,
        Map.of("capaNo", capa.getCapaNo()));
    eventPublisher.publish(
        new CapaVerifiedEvent(capa.getId(), capa.getCompanyId(), capa.getSupplierId()));
    return view(capaMapper.findById(id).orElseThrow());
  }

  @Transactional
  public CapaView closeCapa(long id) {
    Capa capa =
        capaMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.CAPA_NOT_FOUND));
    if (capaMapper.markClosed(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.CAPA_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.CAPA_CLOSE,
        AuditTargetType.CAPA,
        id,
        Map.of("capaNo", capa.getCapaNo()));
    return view(capaMapper.findById(id).orElseThrow());
  }

  @Transactional(readOnly = true)
  public PageResult<Ncr> pageNcr(int page, int size, Long companyId, String status) {
    PageQuery query = PageQuery.of(page, size);
    NcrStatus parsed = parseNcrStatus(status);
    return PageResult.of(
        ncrMapper.findPage(companyId, parsed, query.offset(), query.size()),
        ncrMapper.countBy(companyId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public NcrView ncrDetail(long id) {
    return view(
        ncrMapper.findById(id).orElseThrow(() -> new BizException(ErrorCode.NCR_NOT_FOUND)));
  }

  @Transactional(readOnly = true)
  public PageResult<Capa> pageCapa(int page, int size, Long companyId, String status) {
    PageQuery query = PageQuery.of(page, size);
    CapaStatus parsed = parseCapaStatus(status);
    return PageResult.of(
        capaMapper.findPage(companyId, parsed, query.offset(), query.size()),
        capaMapper.countBy(companyId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public CapaView capaDetail(long id) {
    return view(
        capaMapper.findById(id).orElseThrow(() -> new BizException(ErrorCode.CAPA_NOT_FOUND)));
  }

  private static NcrView view(Ncr ncr) {
    return new NcrView(
        ncr.getId(),
        ncr.getNcrNo(),
        ncr.getCompanyId(),
        ncr.getSourceType(),
        ncr.getSourceId(),
        ncr.getInspectionId(),
        ncr.getItemId(),
        ncr.getSupplierId(),
        ncr.getLotNo(),
        ncr.getQty(),
        ncr.getDefect(),
        ncr.getStatus(),
        ncr.getDisposition(),
        ncr.getReason(),
        ncr.getCreatedAt(),
        ncr.getDispositionedAt(),
        ncr.getClosedAt());
  }

  private static CapaView view(Capa capa) {
    return new CapaView(
        capa.getId(),
        capa.getCapaNo(),
        capa.getCompanyId(),
        capa.getNcrId(),
        capa.getSupplierId(),
        capa.getRootCause(),
        capa.getActionPlan(),
        capa.getStatus(),
        capa.getCreatedAt(),
        capa.getVerifiedBy(),
        capa.getVerifiedAt(),
        capa.getClosedAt());
  }

  private static NcrDisposition parseDisposition(String disposition) {
    try {
      return NcrDisposition.valueOf(disposition.trim().toUpperCase());
    } catch (Exception ex) {
      throw new BizException(ErrorCode.NCR_STATUS_INVALID, "处置方式不合法：" + disposition);
    }
  }

  private static NcrStatus parseNcrStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return NcrStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "NCR 状态不合法：" + status);
    }
  }

  private static CapaStatus parseCapaStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return CapaStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "CAPA 状态不合法：" + status);
    }
  }
}

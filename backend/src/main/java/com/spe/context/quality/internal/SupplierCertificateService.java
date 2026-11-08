package com.spe.context.quality.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 供应商证书（docs/erp/37 §4.4）：有效期状态实时计算。 */
@Service
@RequiredArgsConstructor
public class SupplierCertificateService {

  private final SupplierCertificateMapper certificateMapper;
  private final SupplierQueryFacade supplierQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public SupplierCertificateView create(
      long operatorId,
      long companyId,
      long supplierId,
      Long itemId,
      String certType,
      String certNo,
      LocalDate validFrom,
      LocalDate validTo) {
    supplierQueryFacade
        .findById(supplierId)
        .filter(com.spe.context.supplier.api.SupplierBrief::enabled)
        .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_NOT_FOUND));
    if (certType == null || certType.isBlank()
        || certNo == null || certNo.isBlank()
        || validFrom == null
        || validTo == null
        || validTo.isBefore(validFrom)) {
      throw new BizException(ErrorCode.SUPPLIER_CERTIFICATE_INVALID);
    }
    SupplierCertificate certificate = new SupplierCertificate();
    certificate.setCompanyId(companyId);
    certificate.setSupplierId(supplierId);
    certificate.setItemId(itemId);
    certificate.setCertType(certType.trim().toUpperCase());
    certificate.setCertNo(certNo.trim());
    certificate.setValidFrom(validFrom);
    certificate.setValidTo(validTo);
    certificate.setStatus(CertificateStatus.VALID);
    certificate.setCreatedBy(operatorId);
    try {
      certificateMapper.insert(certificate);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.SUPPLIER_CERTIFICATE_INVALID, "证书编号已存在");
    }
    auditFacade.record(
        AuditAction.SUPPLIER_CERTIFICATE_CREATE,
        AuditTargetType.SUPPLIER_CERTIFICATE,
        certificate.getId(),
        Map.of("supplierId", supplierId, "certNo", certificate.getCertNo(), "type", certType));
    return view(certificate);
  }

  @Transactional(readOnly = true)
  public PageResult<SupplierCertificateView> page(
      int page, int size, Long companyId, Long supplierId) {
    PageQuery query = PageQuery.of(page, size);
    List<SupplierCertificateView> rows =
        certificateMapper.findPage(companyId, supplierId, query.offset(), query.size()).stream()
            .map(SupplierCertificateService::view)
            .toList();
    return PageResult.of(
        rows,
        certificateMapper.countBy(companyId, supplierId),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public List<SupplierCertificateView> expiring(long companyId, int days) {
    if (days < 1 || days > 365) {
      throw new BizException(ErrorCode.BAD_REQUEST, "预警天数必须在 1~365 之间");
    }
    return certificateMapper
        .findExpiring(companyId, LocalDate.now(ZoneOffset.UTC).plusDays(days))
        .stream()
        .map(SupplierCertificateService::view)
        .toList();
  }

  private static SupplierCertificateView view(SupplierCertificate certificate) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    long days = ChronoUnit.DAYS.between(today, certificate.getValidTo());
    String effective =
        certificate.getStatus() == CertificateStatus.REVOKED
            ? "REVOKED"
            : days < 0 ? "EXPIRED" : days <= 30 ? "EXPIRING" : "VALID";
    return new SupplierCertificateView(
        certificate.getId(),
        certificate.getCompanyId(),
        certificate.getSupplierId(),
        certificate.getItemId(),
        certificate.getCertType(),
        certificate.getCertNo(),
        certificate.getValidFrom(),
        certificate.getValidTo(),
        certificate.getStatus(),
        effective,
        days);
  }
}

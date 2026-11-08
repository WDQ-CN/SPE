package com.spe.context.masterdata.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.QualificationStatus;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 供应商扩展（1:1）：税号/银行/账期/币种/准入/证书（docs/erp/31、52）。 */
@Service
@RequiredArgsConstructor
public class SupplierExtensionService {

  private final SupplierExtensionMapper extensionMapper;
  private final SupplierQueryFacade supplierQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional(readOnly = true)
  public Optional<SupplierExtensionView> get(long supplierId) {
    return extensionMapper.findById(supplierId).map(SupplierExtensionView::of);
  }

  @Transactional
  public SupplierExtensionView upsert(
      long supplierId,
      String taxNo,
      String bankAccount,
      String bankName,
      String paymentTerms,
      String currency,
      String incoterms,
      QualificationStatus qualificationStatus,
      LocalDate certificateExpiry,
      int version) {
    supplierQueryFacade
        .findById(supplierId)
        .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_NOT_FOUND));
    String normalizedCurrency = MasterDataSupport.upper(currency);
    Optional<SupplierExtension> existing = extensionMapper.findById(supplierId);

    if (existing.isEmpty()) {
      if (version != 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
      SupplierExtension extension = new SupplierExtension();
      extension.setSupplierId(supplierId);
      extension.setTaxNo(MasterDataSupport.trim(taxNo));
      extension.setBankAccount(MasterDataSupport.trim(bankAccount));
      extension.setBankName(MasterDataSupport.trim(bankName));
      extension.setPaymentTerms(MasterDataSupport.trim(paymentTerms));
      extension.setCurrency(normalizedCurrency);
      extension.setIncoterms(MasterDataSupport.trim(incoterms));
      extension.setQualificationStatus(qualificationStatus);
      extension.setCertificateExpiry(certificateExpiry);
      extension.setVersion(0);
      extensionMapper.insert(extension);
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("supplierId", supplierId);
      detail.put("qualificationStatus", qualificationStatus);
      detail.put("currency", normalizedCurrency);
      auditFacade.record(
          AuditAction.SUPPLIER_EXTENSION_UPDATE, AuditTargetType.SUPPLIER, supplierId, detail);
      return SupplierExtensionView.of(extension);
    }

    SupplierExtension current = existing.get();
    if (!current.getVersion().equals(version)) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    if (extensionMapper.updateProfile(
            supplierId,
            MasterDataSupport.trim(taxNo),
            MasterDataSupport.trim(bankAccount),
            MasterDataSupport.trim(bankName),
            MasterDataSupport.trim(paymentTerms),
            normalizedCurrency,
            MasterDataSupport.trim(incoterms),
            qualificationStatus,
            certificateExpiry,
            version)
        == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "bankName", current.getBankName(), bankName);
    AuditDetails.putIfChanged(detail, "paymentTerms", current.getPaymentTerms(), paymentTerms);
    AuditDetails.putIfChanged(detail, "currency", current.getCurrency(), normalizedCurrency);
    AuditDetails.putIfChanged(detail, "incoterms", current.getIncoterms(), incoterms);
    AuditDetails.putIfChanged(
        detail, "qualificationStatus", current.getQualificationStatus(), qualificationStatus);
    AuditDetails.putIfChanged(
        detail, "certificateExpiry", current.getCertificateExpiry(), certificateExpiry);
    if (!Objects.equals(current.getTaxNo(), MasterDataSupport.trim(taxNo))) {
      detail.put("taxNoChanged", true);
    }
    if (!Objects.equals(current.getBankAccount(), MasterDataSupport.trim(bankAccount))) {
      detail.put("bankAccountChanged", true);
    }
    auditFacade.record(
        AuditAction.SUPPLIER_EXTENSION_UPDATE, AuditTargetType.SUPPLIER, supplierId, detail);
    return new SupplierExtensionView(
        supplierId,
        SupplierExtensionView.mask(MasterDataSupport.trim(taxNo)),
        SupplierExtensionView.mask(MasterDataSupport.trim(bankAccount)),
        MasterDataSupport.trim(bankName),
        MasterDataSupport.trim(paymentTerms),
        normalizedCurrency,
        MasterDataSupport.trim(incoterms),
        qualificationStatus,
        certificateExpiry,
        version + 1);
  }
}

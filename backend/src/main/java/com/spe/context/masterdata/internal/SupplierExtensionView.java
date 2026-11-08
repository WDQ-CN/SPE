package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.QualificationStatus;
import java.time.LocalDate;

/** 供应商扩展投影；税号/银行账号脱敏。 */
public record SupplierExtensionView(
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

  static SupplierExtensionView of(SupplierExtension extension) {
    return new SupplierExtensionView(
        extension.getSupplierId(),
        mask(extension.getTaxNo()),
        mask(extension.getBankAccount()),
        extension.getBankName(),
        extension.getPaymentTerms(),
        extension.getCurrency(),
        extension.getIncoterms(),
        extension.getQualificationStatus(),
        extension.getCertificateExpiry(),
        extension.getVersion());
  }

  static String mask(String value) {
    if (value == null || value.length() <= 4) {
      return value;
    }
    return "****" + value.substring(value.length() - 4);
  }
}

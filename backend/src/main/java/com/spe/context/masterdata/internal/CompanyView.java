package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.MasterDataStatus;

/** 公司列表/详情投影；taxNo 返回脱敏值，不泄露完整税号。 */
public record CompanyView(
    long id,
    String code,
    String name,
    String baseCurrency,
    String taxNo,
    String country,
    MasterDataStatus status,
    int version) {

  static CompanyView of(Company company) {
    return new CompanyView(
        company.getId(),
        company.getCode(),
        company.getName(),
        company.getBaseCurrency(),
        maskTaxNo(company.getTaxNo()),
        company.getCountry(),
        company.getStatus(),
        company.getVersion());
  }

  static String maskTaxNo(String taxNo) {
    if (taxNo == null || taxNo.length() <= 4) {
      return taxNo;
    }
    return "****" + taxNo.substring(taxNo.length() - 4);
  }
}

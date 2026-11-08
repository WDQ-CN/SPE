package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.MasterDataStatus;
import java.math.BigDecimal;

/** 客户投影；税号脱敏。 */
public record CustomerView(
    long id,
    String code,
    String name,
    String taxNo,
    String address,
    String contactName,
    String contactPhone,
    String currency,
    String paymentTerms,
    BigDecimal creditLimit,
    BigDecimal creditUsed,
    MasterDataStatus status,
    int version) {

  static CustomerView of(Customer customer) {
    return new CustomerView(
        customer.getId(),
        customer.getCode(),
        customer.getName(),
        mask(customer.getTaxNo()),
        customer.getAddress(),
        customer.getContactName(),
        customer.getContactPhone(),
        customer.getCurrency(),
        customer.getPaymentTerms(),
        customer.getCreditLimit(),
        customer.getCreditUsed(),
        customer.getStatus(),
        customer.getVersion());
  }

  static String mask(String value) {
    if (value == null || value.length() <= 4) {
      return value;
    }
    return "****" + value.substring(value.length() - 4);
  }
}

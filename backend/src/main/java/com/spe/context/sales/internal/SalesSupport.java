package com.spe.context.sales.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.CustomerBrief;
import com.spe.context.masterdata.api.ItemBrief;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 销售单据号与金额计算。 */
final class SalesSupport {

  private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private SalesSupport() {}

  static String docNo(String prefix) {
    String random =
        UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    return prefix + "-" + LocalDate.now().format(DOC_DATE) + "-" + random;
  }

  static BigDecimal money(BigDecimal value) {
    return value.setScale(4, RoundingMode.HALF_UP);
  }

  static BigDecimal quantity(BigDecimal value) {
    return value.setScale(6, RoundingMode.HALF_UP);
  }

  static BigDecimal rate(BigDecimal value, String label) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new BizException(ErrorCode.SALES_ORDER_LINE_INVALID, label + "必须在 0~1 之间");
    }
    return value;
  }

  static String resolveCurrency(
      CompanyBrief company, CustomerBrief customer, String currency) {
    String normalized = currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
    if (normalized == null || normalized.isEmpty()) {
      normalized = customer.currency();
    }
    if (normalized == null || normalized.isEmpty()) {
      normalized = company.baseCurrency();
    }
    if (!company.baseCurrency().equals(normalized)) {
      throw new BizException(
          ErrorCode.SALES_ORDER_LINE_INVALID, "E3 暂不支持多币种销售，币种必须等于公司本位币");
    }
    return normalized;
  }

  static CalculatedLine calculate(SalesLineCommand command, ItemBrief item) {
    if (command.qty() == null || command.qty().signum() <= 0) {
      throw new BizException(ErrorCode.SALES_ORDER_LINE_INVALID, "数量必须大于 0");
    }
    if (command.unitPrice() == null || command.unitPrice().signum() < 0) {
      throw new BizException(ErrorCode.SALES_ORDER_LINE_INVALID, "单价不能为负");
    }
    BigDecimal quantity = quantity(command.qty());
    BigDecimal price = money(command.unitPrice());
    BigDecimal discount = rate(command.discountRate(), "折扣率");
    BigDecimal taxRate = rate(command.taxRate(), "税率");
    BigDecimal net =
        money(quantity.multiply(price).multiply(BigDecimal.ONE.subtract(discount)));
    BigDecimal tax = money(net.multiply(taxRate));
    String uom =
        command.uom() == null || command.uom().isBlank()
            ? item.baseUom()
            : command.uom().trim().toUpperCase(Locale.ROOT);
    return new CalculatedLine(item.id(), quantity, uom, price, discount, taxRate, net, tax);
  }

  record CalculatedLine(
      long itemId,
      BigDecimal qty,
      String uom,
      BigDecimal unitPrice,
      BigDecimal discountRate,
      BigDecimal taxRate,
      BigDecimal netAmount,
      BigDecimal taxAmount) {

    BigDecimal totalAmount() {
      return netAmount.add(taxAmount);
    }
  }
}

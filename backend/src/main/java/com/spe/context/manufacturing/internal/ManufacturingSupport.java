package com.spe.context.manufacturing.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 制造单号与金额规范化。 */
final class ManufacturingSupport {

  private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private ManufacturingSupport() {}

  static String docNo(String prefix) {
    String random =
        UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    return prefix + "-" + LocalDate.now().format(DOC_DATE) + "-" + random;
  }

  static BigDecimal qty(BigDecimal value) {
    return value.setScale(6, RoundingMode.HALF_UP);
  }

  static BigDecimal money(BigDecimal value) {
    return value.setScale(4, RoundingMode.HALF_UP);
  }
}

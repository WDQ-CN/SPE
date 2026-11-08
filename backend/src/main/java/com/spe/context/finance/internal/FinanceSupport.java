package com.spe.context.finance.internal;

import com.spe.common.sql.SqlKeywords;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 财务文本/金额/凭证号规范化。 */
final class FinanceSupport {

  private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private FinanceSupport() {}

  static BigDecimal money(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.setScale(4, RoundingMode.HALF_UP);
  }

  static String keyword(String value) {
    String trimmed = value == null ? null : value.trim();
    return trimmed == null || trimmed.isEmpty() ? null : SqlKeywords.escapeLike(trimmed);
  }

  static String docNo() {
    String random = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    return "JE-" + LocalDate.now().format(DOC_DATE) + "-" + random;
  }
}

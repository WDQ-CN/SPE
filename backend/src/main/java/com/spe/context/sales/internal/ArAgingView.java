package com.spe.context.sales.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** AR 账龄报表。 */
public record ArAgingView(
    long companyId,
    LocalDate asOf,
    BigDecimal totalOutstanding,
    BigDecimal current,
    BigDecimal days1To30,
    BigDecimal days31To60,
    BigDecimal days61To90,
    BigDecimal daysOver90,
    List<Row> rows) {

  public record Row(
      long invoiceId,
      String invoiceNo,
      long customerId,
      String customerName,
      LocalDate dueDate,
      long daysPastDue,
      String bucket,
      BigDecimal outstanding) {}
}

package com.spe.context.analytics.internal;

import java.math.BigDecimal;
import java.util.List;

/** 分析看板的响应投影（M5 W4；实体不出上下文，全部 record）。 */
public final class AnalyticsViews {

  private AnalyticsViews() {}

  public record Period(int year, int quarter) {}

  public record SupplierOption(long id, String code, String name, String category) {}

  public record DimensionPoint(String dimensionName, BigDecimal score, String grade) {}

  public record TrendPoint(
      int year,
      int quarter,
      BigDecimal totalScore,
      String totalGrade,
      List<DimensionPoint> dimensions) {}

  public record Trend(
      long supplierId, String supplierCode, String supplierName, List<TrendPoint> periods) {}

  public record RankingRow(
      long supplierId,
      String supplierCode,
      String supplierName,
      String category,
      BigDecimal totalScore,
      String totalGrade,
      BigDecimal previousScore,
      Integer previousYear,
      Integer previousQuarter,
      BigDecimal delta) {}

  public record Ranking(int year, int quarter, List<RankingRow> rows) {}

  public record GradeCount(String grade, long count) {}

  public record CompareRow(
      long supplierId,
      String supplierCode,
      String supplierName,
      BigDecimal totalScore,
      String totalGrade,
      List<DimensionPoint> dimensions) {}

  public record Compare(int year, int quarter, List<CompareRow> rows) {}
}

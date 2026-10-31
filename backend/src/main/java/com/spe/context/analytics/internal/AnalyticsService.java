package com.spe.context.analytics.internal;

import com.spe.common.period.Quarters;

import com.spe.common.sql.SqlKeywords;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分析看板（01 B1/B2/B4/B5；M5 W4）。
 *
 * <p>口径（22 §2 D3/D6）：只读 `EFFECTIVE`；周期按 `(year, quarter)`；**环比只认上一个日历季度**
 * （跨年 Q1→上一年 Q4；该季度无结果 ⇒ null，不跨缺口回溯）；趋势按**维度名并集**、缺口 null；
 * 分类/等级过滤只作用于排行，且分类为空的供应商不会被误吞。
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

  private static final Set<String> GRADES = Set.of("A", "B", "C", "D");
  private static final List<String> GRADE_ORDER = List.of("A", "B", "C", "D");

  private final AnalyticsQueryMapper mapper;

  @Transactional(readOnly = true)
  public List<AnalyticsViews.Period> periods() {
    return mapper.findPeriods().stream()
        .map(row -> new AnalyticsViews.Period(row.getYear(), row.getQuarter()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<AnalyticsViews.SupplierOption> suppliers(String keyword) {
    String like =
        keyword == null || keyword.isBlank() ? null : SqlKeywords.escapeLike(keyword.trim());
    return mapper.findSuppliersWithResults(like).stream()
        .map(
            row ->
                new AnalyticsViews.SupplierOption(
                    row.getId(), row.getCode(), row.getName(), row.getCategory()))
        .toList();
  }

  @Transactional(readOnly = true)
  public AnalyticsViews.Trend trend(long supplierId) {
    AnalyticsQueryMapper.SupplierRow supplier =
        mapper
            .findSupplier(supplierId)
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_NOT_FOUND));
    List<AnalyticsQueryMapper.TrendPointRow> points = mapper.findTrendPoints(supplierId);
    if (points.isEmpty()) {
      return new AnalyticsViews.Trend(
          supplierId, supplier.getCode(), supplier.getName(), List.of());
    }
    Map<Long, List<AnalyticsQueryMapper.SnapshotRow>> snapshots =
        mapper
            .findSnapshots(points.stream().map(AnalyticsQueryMapper.TrendPointRow::getTaskSupplierId).toList())
            .stream()
            .collect(Collectors.groupingBy(AnalyticsQueryMapper.SnapshotRow::getTaskSupplierId));

    // 维度名并集：按首次出现的顺序（跨版本模板可能维度不同；缺口补 null）
    Set<String> dimensionNames = new LinkedHashSet<>();
    for (AnalyticsQueryMapper.TrendPointRow point : points) {
      for (AnalyticsQueryMapper.SnapshotRow row :
          snapshots.getOrDefault(point.getTaskSupplierId(), List.of())) {
        dimensionNames.add(row.getDimensionName());
      }
    }

    List<AnalyticsViews.TrendPoint> result = new ArrayList<>(points.size());
    for (AnalyticsQueryMapper.TrendPointRow point : points) {
      Map<String, AnalyticsQueryMapper.SnapshotRow> byName =
          snapshots.getOrDefault(point.getTaskSupplierId(), List.of()).stream()
              .collect(
                  Collectors.toMap(
                      AnalyticsQueryMapper.SnapshotRow::getDimensionName,
                      Function.identity(),
                      (left, right) -> right));
      List<AnalyticsViews.DimensionPoint> dimensions = new ArrayList<>(dimensionNames.size());
      for (String name : dimensionNames) {
        AnalyticsQueryMapper.SnapshotRow row = byName.get(name);
        dimensions.add(
            new AnalyticsViews.DimensionPoint(
                name, row == null ? null : row.getScore(), row == null ? null : row.getGrade()));
      }
      result.add(
          new AnalyticsViews.TrendPoint(
              point.getYear(),
              point.getQuarter(),
              point.getTotalScore(),
              point.getTotalGrade(),
              dimensions));
    }
    return new AnalyticsViews.Trend(
        supplierId, supplier.getCode(), supplier.getName(), result);
  }

  @Transactional(readOnly = true)
  public AnalyticsViews.Ranking ranking(int year, int quarter, String category, String grade) {
    String gradeFilter = normalizeGrade(grade);
    String categoryFilter = category == null || category.isBlank() ? null : category.trim();
    List<AnalyticsQueryMapper.RankingRow> rows =
        mapper.findRanking(year, quarter, categoryFilter, gradeFilter);
    if (rows.isEmpty()) {
      return new AnalyticsViews.Ranking(year, quarter, List.of());
    }
    int[] previous = Quarters.previous(year, quarter);
    Map<Long, BigDecimal> previousScores =
        mapper.findPeriodScores(previous[0], previous[1]).stream()
            .collect(
                Collectors.toMap(
                    AnalyticsQueryMapper.ScoreRow::getSupplierId,
                    AnalyticsQueryMapper.ScoreRow::getTotalScore,
                    (left, right) -> right));
    List<AnalyticsViews.RankingRow> result = new ArrayList<>(rows.size());
    for (AnalyticsQueryMapper.RankingRow row : rows) {
      BigDecimal before = previousScores.get(row.getSupplierId());
      BigDecimal delta =
          before == null || row.getTotalScore() == null
              ? null
              : row.getTotalScore().subtract(before);
      result.add(
          new AnalyticsViews.RankingRow(
              row.getSupplierId(),
              row.getCode(),
              row.getName(),
              row.getCategory(),
              row.getTotalScore(),
              row.getTotalGrade(),
              before,
              previous[0],
              previous[1],
              delta));
    }
    return new AnalyticsViews.Ranking(year, quarter, result);
  }

  /** 等级分布：固定 A/B/C/D 四条（0 也返回，前端不用补空）。 */
  @Transactional(readOnly = true)
  public List<AnalyticsViews.GradeCount> gradeDistribution(int year, int quarter) {
    Map<String, Long> counts =
        mapper.findGradeCounts(year, quarter).stream()
            .collect(
                Collectors.toMap(
                    AnalyticsQueryMapper.GradeCountRow::getGrade,
                    AnalyticsQueryMapper.GradeCountRow::getCnt,
                    (left, right) -> right));
    return GRADE_ORDER.stream()
        .map(grade -> new AnalyticsViews.GradeCount(grade, counts.getOrDefault(grade, 0L)))
        .toList();
  }

  @Transactional(readOnly = true)
  public AnalyticsViews.Compare compare(int year, int quarter, List<Long> supplierIds) {
    List<Long> requested =
        supplierIds == null
            ? List.of()
            : supplierIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
    if (requested.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "请选择至少一个供应商");
    }
    List<AnalyticsQueryMapper.CompareRow> rows = mapper.findCompareRows(year, quarter, requested);
    Map<Long, AnalyticsQueryMapper.CompareRow> bySupplier =
        rows.stream()
            .collect(
                Collectors.toMap(
                    AnalyticsQueryMapper.CompareRow::getSupplierId,
                    Function.identity(),
                    (left, right) -> right));
    List<Long> missing =
        requested.stream().filter(id -> !bySupplier.containsKey(id)).toList();
    if (!missing.isEmpty()) {
      throw new BizException(
          ErrorCode.BAD_REQUEST,
          "以下供应商在该周期没有生效结果：" + missing.stream().map(id -> "id=" + id).collect(Collectors.joining("、")));
    }
    Map<Long, List<AnalyticsQueryMapper.SnapshotRow>> snapshots =
        mapper
            .findSnapshots(rows.stream().map(AnalyticsQueryMapper.CompareRow::getTaskSupplierId).toList())
            .stream()
            .collect(Collectors.groupingBy(AnalyticsQueryMapper.SnapshotRow::getTaskSupplierId));

    List<AnalyticsViews.CompareRow> result = new ArrayList<>(requested.size());
    for (Long supplierId : requested) {
      AnalyticsQueryMapper.CompareRow row = bySupplier.get(supplierId);
      List<AnalyticsViews.DimensionPoint> dimensions =
          snapshots.getOrDefault(row.getTaskSupplierId(), List.of()).stream()
              .map(
                  snapshot ->
                      new AnalyticsViews.DimensionPoint(
                          snapshot.getDimensionName(), snapshot.getScore(), snapshot.getGrade()))
              .toList();
      result.add(
          new AnalyticsViews.CompareRow(
              row.getSupplierId(),
              row.getCode(),
              row.getName(),
              row.getTotalScore(),
              row.getTotalGrade(),
              dimensions));
    }
    return new AnalyticsViews.Compare(year, quarter, result);
  }

  private static String normalizeGrade(String grade) {
    if (grade == null || grade.isBlank()) {
      return null;
    }
    String value = grade.trim().toUpperCase();
    if (!GRADES.contains(value)) {
      throw new BizException(ErrorCode.BAD_REQUEST, "等级过滤只支持 A/B/C/D：" + grade);
    }
    return value;
  }

}

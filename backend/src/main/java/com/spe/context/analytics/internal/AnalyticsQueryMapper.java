package com.spe.context.analytics.internal;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分析看板的**只读 QueryMapper**（05 §8.3；R-B 只允许 analytics/alert 拥有 QueryMapper）。
 *
 * <p>只读 `EFFECTIVE` 评估单与 `task_supplier_dimension_result` 快照；不碰任何写路径。
 * `keyword` 由 Service 用 `SqlKeywords.escapeLike` 转义后传入（21 的口径）。
 */
@Mapper
public interface AnalyticsQueryMapper {

  /** 有生效结果的周期（倒序）。 */
  @Select(
      "SELECT DISTINCT t.year, t.quarter FROM task_supplier ts"
          + " JOIN eval_task t ON t.id = ts.task_id"
          + " WHERE ts.status = 'EFFECTIVE' AND t.status != 'CANCELED'"
          + " ORDER BY t.year DESC, t.quarter DESC")
  List<PeriodRow> findPeriods();

  /** 有生效结果的供应商（**含停用**、含分类为空；结果历史必须能选到）。 */
  @Select(
      """
      <script>
      SELECT s.id, s.code, s.name, s.category FROM supplier s
      WHERE EXISTS (
        SELECT 1 FROM task_supplier ts WHERE ts.supplier_id = s.id AND ts.status = 'EFFECTIVE'
      )
      <if test="keyword != null and keyword != ''">
        AND (s.code LIKE CONCAT('%', #{keyword}, '%') OR s.name LIKE CONCAT('%', #{keyword}, '%'))
      </if>
      ORDER BY s.code LIMIT 500
      </script>
      """)
  List<SupplierRow> findSuppliersWithResults(@Param("keyword") String keyword);

  @Select(
      "SELECT id, code, name, category FROM supplier WHERE id = #{id}")
  Optional<SupplierRow> findSupplier(@Param("id") long id);

  /** 单供应商的跨期有效结果（升序）。 */
  @Select(
      "SELECT ts.id AS task_supplier_id, t.year, t.quarter, ts.total_score, ts.total_grade"
          + " FROM task_supplier ts JOIN eval_task t ON t.id = ts.task_id"
          + " WHERE ts.supplier_id = #{supplierId} AND ts.status = 'EFFECTIVE'"
          + " AND t.status != 'CANCELED' ORDER BY t.year, t.quarter")
  List<TrendPointRow> findTrendPoints(@Param("supplierId") long supplierId);

  /** 维度快照批读（趋势/对比共用）。 */
  @Select(
      """
      <script>
      SELECT task_supplier_id, dimension_name, score, grade
      FROM task_supplier_dimension_result
      WHERE task_supplier_id IN
      <foreach item="item" collection="taskSupplierIds" open="(" separator="," close=")">#{item}</foreach>
      ORDER BY task_supplier_id, dimension_id
      </script>
      """)
  List<SnapshotRow> findSnapshots(
      @Param("taskSupplierIds") Collection<Long> taskSupplierIds);

  /** 排行：当期生效结果（分类/等级过滤只在传值时生效；分类为空的供应商不被误吞）。 */
  @Select(
      """
      <script>
      SELECT ts.supplier_id, s.code, s.name, s.category, ts.total_score, ts.total_grade
      FROM task_supplier ts
      JOIN eval_task t ON t.id = ts.task_id
      JOIN supplier s ON s.id = ts.supplier_id
      WHERE ts.status = 'EFFECTIVE' AND t.status != 'CANCELED'
        AND t.year = #{year} AND t.quarter = #{quarter}
      <if test="category != null and category != ''"> AND s.category = #{category} </if>
      <if test="grade != null and grade != ''"> AND ts.total_grade = #{grade} </if>
      ORDER BY ts.total_score DESC, s.code
      </script>
      """)
  List<RankingRow> findRanking(
      @Param("year") int year,
      @Param("quarter") int quarter,
      @Param("category") String category,
      @Param("grade") String grade);

  /** 某周期的全部有效总分（环比用；不套分类/等级过滤——那是"供应商自己的上一期"）。 */
  @Select(
      "SELECT ts.supplier_id, ts.total_score FROM task_supplier ts"
          + " JOIN eval_task t ON t.id = ts.task_id"
          + " WHERE ts.status = 'EFFECTIVE' AND t.status != 'CANCELED'"
          + " AND t.year = #{year} AND t.quarter = #{quarter}")
  List<ScoreRow> findPeriodScores(@Param("year") int year, @Param("quarter") int quarter);

  @Select(
      "SELECT ts.total_grade AS grade, COUNT(*) AS cnt FROM task_supplier ts"
          + " JOIN eval_task t ON t.id = ts.task_id"
          + " WHERE ts.status = 'EFFECTIVE' AND t.status != 'CANCELED'"
          + " AND t.year = #{year} AND t.quarter = #{quarter}"
          + " GROUP BY ts.total_grade")
  List<GradeCountRow> findGradeCounts(@Param("year") int year, @Param("quarter") int quarter);

  /** 对比：所选供应商在当期的有效结果（缺任一 ⇒ Service 报 400 点名）。 */
  @Select(
      """
      <script>
      SELECT ts.id AS task_supplier_id, ts.supplier_id, s.code, s.name, ts.total_score, ts.total_grade
      FROM task_supplier ts
      JOIN eval_task t ON t.id = ts.task_id
      JOIN supplier s ON s.id = ts.supplier_id
      WHERE ts.status = 'EFFECTIVE' AND t.status != 'CANCELED'
        AND t.year = #{year} AND t.quarter = #{quarter}
        AND ts.supplier_id IN
        <foreach item="item" collection="supplierIds" open="(" separator="," close=")">#{item}</foreach>
      </script>
      """)
  List<CompareRow> findCompareRows(
      @Param("year") int year,
      @Param("quarter") int quarter,
      @Param("supplierIds") Collection<Long> supplierIds);

  @Data
  class PeriodRow {
    private Integer year;
    private Integer quarter;
  }

  @Data
  class SupplierRow {
    private Long id;
    private String code;
    private String name;
    private String category;
  }

  @Data
  class TrendPointRow {
    private Long taskSupplierId;
    private Integer year;
    private Integer quarter;
    private BigDecimal totalScore;
    private String totalGrade;
  }

  @Data
  class SnapshotRow {
    private Long taskSupplierId;
    private String dimensionName;
    private BigDecimal score;
    private String grade;
  }

  @Data
  class RankingRow {
    private Long supplierId;
    private String code;
    private String name;
    private String category;
    private BigDecimal totalScore;
    private String totalGrade;
  }

  @Data
  class ScoreRow {
    private Long supplierId;
    private BigDecimal totalScore;
  }

  @Data
  class GradeCountRow {
    private String grade;
    private Long cnt;
  }

  @Data
  class CompareRow {
    private Long taskSupplierId;
    private Long supplierId;
    private String code;
    private String name;
    private BigDecimal totalScore;
    private String totalGrade;
  }
}

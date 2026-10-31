package com.spe.context.alert.internal;

import com.spe.common.sql.EffectiveResults;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * alert 的**只读 QueryMapper**（05 §8.3 的读放开）：只 `@Select`，不碰任何写路径。
 *
 * <p>三处取数：① 关键维度（规则②）；② 供应商名（可读通知/预警文案）；③ 该供应商最近的有效周期
 * （规则③的跨期数据）。③在定稿事务内执行，**能看到本次刚写入的 EFFECTIVE 行**（同一连接，22 §2 D3）。
 * 行投影用 `@Data` 类而不是 record：MyBatis 对 record 的构造器映射依赖额外配置，项目内一律用手工投影类。
 */
@Mapper
public interface AlertQueryMapper {

  /** 该评估单所用模板版本里 `is_key=1` 的维度（已发布版本冻结 ⇒ join 结果稳定）。 */
  @Select(
      "SELECT d.id, d.name FROM task_supplier ts"
          + " JOIN eval_task t ON t.id = ts.task_id"
          + " JOIN dimension d ON d.template_version_id = t.template_version_id AND d.is_key = 1"
          + " WHERE ts.id = #{taskSupplierId} ORDER BY d.sort_no, d.id")
  List<KeyDimensionRow> findKeyDimensions(@Param("taskSupplierId") long taskSupplierId);

  @Select("SELECT name FROM supplier WHERE id = #{supplierId}")
  Optional<String> findSupplierName(@Param("supplierId") long supplierId);

  /** 最近的有效周期（倒序，含本次定稿——同事务可见）；上限 12 足够 8 期趋势回溯。 */
  @Select(
      "SELECT t.year, t.quarter, ts.total_score AS total_score FROM task_supplier ts"
          + " JOIN eval_task t ON t.id = ts.task_id"
          + " WHERE ts.supplier_id = #{supplierId} AND " + EffectiveResults.CONDITION
          + " ORDER BY t.year DESC, t.quarter DESC LIMIT #{limit}")
  List<PeriodScoreRow> findRecentEffectivePeriods(
      @Param("supplierId") long supplierId, @Param("limit") int limit);

  /** 关键维度投影。 */
  @Data
  class KeyDimensionRow {
    private Long id;
    private String name;
  }

  /** 跨期分数投影。 */
  @Data
  class PeriodScoreRow {
    private Integer year;
    private Integer quarter;
    private BigDecimal totalScore;
  }
}

package com.spe.context.template.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `dimension` 的 Mapper（属主上下文内可见，05 §8.3）。
 *
 * <p>改删都是带 `version` 前置条件的 UPDATE/DELETE（06 §2.4）：维度与指标也带乐观锁列，理由见 12 §E2——
 * 发布靠状态守卫，但**同一草稿上的并发编辑状态相同、守卫无效**，只能靠 `version`。
 */
@Mapper
public interface DimensionMapper extends BaseMapper<Dimension> {

  String COLUMNS =
      "id, template_version_id, name, weight, sort_no, suggested_dept, is_key, version";

  /** 整棵树的第二层（12 §R-15 的"三条查询"之一）。 */
  @Select(
      "SELECT " + COLUMNS
          + " FROM dimension WHERE template_version_id = #{versionId} ORDER BY sort_no, id")
  List<Dimension> findByVersionId(@Param("versionId") long versionId);

  @Select("SELECT " + COLUMNS + " FROM dimension WHERE id = #{id}")
  Optional<Dimension> findById(@Param("id") long id);

  /**
   * 发布校验的聚合视图：一条 SQL 取回每个维度的权重、指标数与指标权重合计。
   *
   * <p>`LEFT JOIN` 是为了**让没有指标的维度也出现在结果里**（`indicatorCount = 0`）——它正是"每维度至少 1 个
   * 指标"（01 T4）要拦的那种，用 `INNER JOIN` 会让违规的维度凭空消失、校验反而通过。
   */
  @Select(
      """
      SELECT d.id                       AS id,
             d.name                     AS name,
             d.weight                   AS dimensionWeight,
             COUNT(i.id)                AS indicatorCount,
             COALESCE(SUM(i.weight), 0) AS indicatorWeightSum
      FROM dimension d
      LEFT JOIN indicator i ON i.dimension_id = d.id
      WHERE d.template_version_id = #{versionId}
      GROUP BY d.id, d.name, d.weight
      ORDER BY d.sort_no, d.id
      """)
  List<DimensionValidationRow> findForValidation(@Param("versionId") long versionId);

  @Update(
      """
      UPDATE dimension
      SET name = #{name}, weight = #{weight}, sort_no = #{sortNo},
          suggested_dept = #{suggestedDept}, is_key = #{isKey}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int update(
      @Param("id") long id,
      @Param("name") String name,
      @Param("weight") BigDecimal weight,
      @Param("sortNo") int sortNo,
      @Param("suggestedDept") String suggestedDept,
      @Param("isKey") boolean isKey,
      @Param("version") int version);

  /**
   * 删除维度（12 §E13）：带 `version` 守卫，0 行 → 3001。
   *
   * <p>**其下的指标由 Service 在同一事务内一并删除**——表上没有外键（06 §4 不要求），不同步删就会留下
   * 孤儿指标：整树读取看不见它们，但自动复制会按 `template_version_id` 把它们一起复制过去，而发布校验
   * 只查"某维度无指标"，孤儿指标永远不会让校验失败。
   */
  @Update("DELETE FROM dimension WHERE id = #{id} AND version = #{version}")
  int delete(@Param("id") long id, @Param("version") int version);
}

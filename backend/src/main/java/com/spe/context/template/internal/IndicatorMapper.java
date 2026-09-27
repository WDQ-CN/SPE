package com.spe.context.template.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `indicator` 的 Mapper（属主上下文内可见，05 §8.3）。
 *
 * <p>`uk_indicator_version_code`（版本内编码唯一）是**数据库兜底**：编码是 Excel 导入与开放 API 定位指标的
 * 依据，靠"先查再插"在并发下会漏（12 §E11），因此写路径既要前置查重（给出友好的 2014），也要把
 * `DuplicateKeyException` 翻译成同一个码。
 */
@Mapper
public interface IndicatorMapper extends BaseMapper<Indicator> {

  String COLUMNS =
      "id, dimension_id, template_version_id, name, code, weight, type, score_mode,"
          + " unit, target_value, standard, sort_no, version, src_indicator_id";

  /** 整棵树的第三层：**按版本一次取全部指标**（不是逐个维度去查）。 */
  @Select(
      "SELECT " + COLUMNS
          + " FROM indicator WHERE template_version_id = #{versionId} ORDER BY dimension_id, sort_no, id")
  List<Indicator> findByVersionId(@Param("versionId") long versionId);

  /** 同 {@link #findByVersionId}，但用共享锁（当前读）——理由见 `DimensionMapper.findByVersionIdForShare`。 */
  @Select(
      "SELECT " + COLUMNS
          + " FROM indicator WHERE template_version_id = #{versionId}"
          + " ORDER BY dimension_id, sort_no, id FOR SHARE")
  List<Indicator> findByVersionIdForShare(@Param("versionId") long versionId);

  @Select("SELECT " + COLUMNS + " FROM indicator WHERE id = #{id}")
  Optional<Indicator> findById(@Param("id") long id);

  /**
   * 与 {@link #findById} 同一条查询，但用排他锁（当前读）——写路径的入口读用它。
   *
   * <p>⚠️ 必须是 `FOR UPDATE` 而不是 `FOR SHARE`（否则同一行的并发修改会形成 S→X 升级死锁）：
   * 完整推理见 `DimensionMapper.findByIdForUpdate` 与 04-076。
   */
  @Select("SELECT " + COLUMNS + " FROM indicator WHERE id = #{id} FOR UPDATE")
  Optional<Indicator> findByIdForUpdate(@Param("id") long id);

  /**
   * 保存时的编码查重（12 §E11）：在**版本内**比，`excludeId` 排除自己那条（改指标时不该被自己挡住）。
   *
   * <p>用 `<script>` + `<if>` 而不是 `id &lt;&gt; #{excludeId}`：后者在 `excludeId` 为 null 时结果是
   * `id &lt;&gt; NULL` → NULL → 恒假，会把"新增"误判成"不重复"的反面（查重直接失效）。
   */
  @Select(
      """
      <script>
      SELECT COUNT(*) FROM indicator
      WHERE template_version_id = #{versionId} AND code = #{code}
      <if test="excludeId != null"> AND id &lt;&gt; #{excludeId}</if>
      </script>
      """)
  long countByCode(
      @Param("versionId") long versionId,
      @Param("code") String code,
      @Param("excludeId") Long excludeId);

  /** 发布时的最后一道查重：正常情况下唯一键已保证无重复，这条是防御（12 §E11 要求"发布时再兜一次"）。 */
  @Select(
      """
      SELECT code FROM indicator
      WHERE template_version_id = #{versionId}
      GROUP BY code HAVING COUNT(*) > 1
      """)
  List<String> findDuplicateCodes(@Param("versionId") long versionId);

  /** TARGET 指标的目标值必须 > 0（01 §6.6）：返回违规的指标编码。 */
  @Select(
      """
      <script>
      SELECT code FROM indicator
      WHERE template_version_id = #{versionId} AND score_mode = 'TARGET'
        AND (target_value IS NULL OR target_value &lt;= 0)
      </script>
      """)
  List<String> findInvalidTargets(@Param("versionId") long versionId);

  /**
   * 改指标：**两道前置条件**——`version`（乐观锁）+ `template_version_id`（**归属版本**）。
   *
   * <p>⚠️ 第二个条件是 04-074 加的**第二道闸**（理由见 `DimensionMapper.update`）：`version` 只说明"这行没
   * 被别人改过"，**不说明它属于我要改的版本**。曾有一条静默写穿的路径（复用草稿时映射不完整 → 旧行 id 落到
   * 已发布版本的行，而那条行的 `version` 恰是调用方手里那个 ⇒ 守卫通过），钉上生效版本号后这类写入在
   * SQL 层不可能成功（0 行 → 3001）。
   */
  @Update(
      """
      UPDATE indicator
      SET name = #{name}, code = #{code}, weight = #{weight}, type = #{type},
          score_mode = #{scoreMode}, unit = #{unit}, target_value = #{targetValue},
          standard = #{standard}, sort_no = #{sortNo}, version = version + 1
      WHERE id = #{id} AND version = #{version} AND template_version_id = #{versionId}
      """)
  int update(
      @Param("id") long id,
      @Param("name") String name,
      @Param("code") String code,
      @Param("weight") BigDecimal weight,
      @Param("type") IndicatorType type,
      @Param("scoreMode") ScoreMode scoreMode,
      @Param("unit") String unit,
      @Param("targetValue") BigDecimal targetValue,
      @Param("standard") String standard,
      @Param("sortNo") int sortNo,
      @Param("version") int version,
      @Param("versionId") long versionId);

  /** 删除指标：两道守卫（`version` + 归属版本），0 行 → 3001。 */
  @Update(
      "DELETE FROM indicator WHERE id = #{id} AND version = #{version}"
          + " AND template_version_id = #{versionId}")
  int delete(
      @Param("id") long id, @Param("version") int version, @Param("versionId") long versionId);

  /**
   * 级联删除某维度下的全部指标（12 §E13）：**删维度时必须同事务调用**，否则留下孤儿指标。
   *
   * <p>不带 `version` 守卫：它随维度的守卫结果一起生效——维度那条 DELETE 已经确认了"这行没被别人改过"，
   * 再对指标逐个加守卫只会让"删一个有 4 个指标的维度"变成 5 次可能失败的操作。
   */
  @Update("DELETE FROM indicator WHERE dimension_id = #{dimensionId}")
  int deleteByDimensionId(@Param("dimensionId") long dimensionId);
}

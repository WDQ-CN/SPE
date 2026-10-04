package com.spe.context.evaluation.internal.scoring;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `score` 的 Mapper —— **只允许 {@link ScoreWriter} 调用**（13 §E7：三源写入从第一天就收敛到一个入口）。
 *
 * <p>写入一律是 `INSERT … ON DUPLICATE KEY UPDATE`（幂等 upsert，唯一键 `uk_score(sheet_id, indicator_id)`）：
 * 它把"新增"与"覆盖"合成一条语句，天然满足 01 §6.7 的"重复导入覆盖未提交数据"，也不会在并发下写出两行。
 */
@Mapper
public interface ScoreMapper extends BaseMapper<Score> {

  String COLUMNS = "id, sheet_id, indicator_id, raw_value, score_value, source, import_batch";

  /**
   * 单行 upsert（手工暂存用）：冲突时覆盖原始值、换算分、来源与批次号。
   *
   * <p>⚠️ `import_batch` 显式写 NULL/值：非导入来源必须把它清成 NULL，否则会把"上一次导入的批次号"
   * 当成这一次的（13 §E2 的说明）。
   */
  @Insert(
      """
      INSERT INTO score (sheet_id, indicator_id, raw_value, score_value, source, import_batch)
      VALUES (#{sheetId}, #{indicatorId}, #{rawValue}, #{scoreValue}, #{source}, #{importBatch})
      ON DUPLICATE KEY UPDATE
        raw_value = VALUES(raw_value),
        score_value = VALUES(score_value),
        source = VALUES(source),
        import_batch = VALUES(import_batch),
        updated_at = CURRENT_TIMESTAMP(3)
      """)
  int upsert(
      @Param("sheetId") long sheetId,
      @Param("indicatorId") long indicatorId,
      @Param("rawValue") java.math.BigDecimal rawValue,
      @Param("scoreValue") java.math.BigDecimal scoreValue,
      @Param("source") String source,
      @Param("importBatch") String importBatch);

  /** 批量 upsert（Excel 导入用）：一条语句写多格，避免逐格往返（13 §E16 的同族经验）。 */
  @Insert(
      """
      <script>
      INSERT INTO score (sheet_id, indicator_id, raw_value, score_value, source, import_batch) VALUES
      <foreach item="item" collection="items" separator=",">
        (#{item.sheetId}, #{item.indicatorId}, #{item.rawValue}, #{item.scoreValue}, #{item.source}, #{item.importBatch})
      </foreach>
      ON DUPLICATE KEY UPDATE
        raw_value = VALUES(raw_value),
        score_value = VALUES(score_value),
        source = VALUES(source),
        import_batch = VALUES(import_batch),
        updated_at = CURRENT_TIMESTAMP(3)
      </script>
      """)
  int upsertBatch(@Param("items") List<Score> items);

  /**
   * 只纠一行的"值"（04-096 / `03 §34` P1-2）：**不碰** `source` 与 `import_batch`——它们记的是
   * "这格是怎么来的"（导入还是手工），属**历史事实**；被纠正的只是算错了的分。
   *
   * <p>用 `UPDATE` 而不是 upsert：调用它的场景（提交时发现库里 `score_value` 与重算不一致）里
   * 行**一定已存在**；而 upsert 的 INSERT 分支需要 `source`，会把 NOT NULL 约束撞出来
   * （04-096 新加的用例当场抓到过这个错误）。
   */
  @Update(
      "UPDATE score SET raw_value = #{rawValue}, score_value = #{scoreValue},"
          + " updated_at = CURRENT_TIMESTAMP(3)"
          + " WHERE sheet_id = #{sheetId} AND indicator_id = #{indicatorId}")
  int updateValues(
      @Param("sheetId") long sheetId,
      @Param("indicatorId") long indicatorId,
      @Param("rawValue") java.math.BigDecimal rawValue,
      @Param("scoreValue") java.math.BigDecimal scoreValue);

  @Select("SELECT " + COLUMNS + " FROM score WHERE sheet_id = #{sheetId} ORDER BY indicator_id")
  List<Score> findBySheetId(@Param("sheetId") long sheetId);

  /**
   * 一个任务下**全部**评分记录（供进度看板一次取回内存后按供应商分组试算，13 §E14）。
   *
   * <p>刻意不做"逐供应商回查"：试算是纯函数，一次取回比 N 次往返少一个数量级的 SQL。
   */
  @Select(
      "SELECT sc.id, sc.sheet_id, sc.indicator_id, sc.raw_value, sc.score_value, sc.source, sc.import_batch"
          + " FROM score sc JOIN sheet s ON s.id = sc.sheet_id"
          + " JOIN task_supplier ts ON ts.id = s.task_supplier_id WHERE ts.task_id = #{taskId}")
  List<Score> findByTaskId(@Param("taskId") long taskId);

  @Select(
      """
      <script>
      SELECT id, sheet_id, indicator_id, raw_value, score_value, source, import_batch
      FROM score WHERE sheet_id IN
      <foreach item="item" collection="sheetIds" open="(" separator="," close=")">#{item}</foreach>
      </script>
      """)
  List<Score> findBySheetIds(@Param("sheetIds") Collection<Long> sheetIds);
}

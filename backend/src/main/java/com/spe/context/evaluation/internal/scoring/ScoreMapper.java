package com.spe.context.evaluation.internal.scoring;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
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

  /**
   * 清空指定格子（03 §38 的 P2-15：把"删值"表达进契约）。
   *
   * <p>⚠️ **0 行不是错误，也不套 3xxx**：`06 §2.4` 管的是"状态迁移"的守卫 UPDATE——那里的病是
   * "前置状态不满足却当成成功"。这里没有状态机：删一格要么删掉、要么它本来就没值，两种都是**幂等的成功**。
   * 给它补一个"3001"反而是发明了不存在的那种失败。
   *
   * <p>不需要额外的锁定读：调用方（`ScoreWriter`）已经先 `findByIdForUpdate(sheetId)` 锁住评分单行，
   * "可写 + 是指派给我"都在那把 X 锁保护的原子段里判完，所以这里的删除与守卫之间没有 TOCTOU 窗口。
   * 指标是否属于本模板版本也由调用方校验（不属于 ⇒ 2015），所以这里不重复查一遍快照。
   */
  @Delete(
      """
      <script>
      DELETE FROM score WHERE sheet_id = #{sheetId} AND indicator_id IN
      <foreach item="item" collection="indicatorIds" open="(" separator="," close=")">#{item}</foreach>
      </script>
      """)
  int deleteValues(@Param("sheetId") long sheetId, @Param("indicatorIds") List<Long> indicatorIds);

  @Select("SELECT " + COLUMNS + " FROM score WHERE sheet_id = #{sheetId} ORDER BY indicator_id")
  List<Score> findBySheetId(@Param("sheetId") long sheetId);

  /**
   * **提交路径专用**的当前读（`03 §38` 的 P1-1）。锁模式按 `04-076` 的自家判据定：
   * 本事务后面可能 `correctValues` 写这些行 ⇒ 取 X，不取 S（S→X 升级正是 `04-076` 付过代价的形态）。
   *
   * <p>⚠️ **不要就地给 {@link #findBySheetId} 加锁**：它同时被读路径（GET 打分页 / 试算）用着，
   * 加锁等于让读接口去排队。
   *
   * <p>改成普通读会让 `ScoringServiceConcurrencyIntegrationTest` 变红——这是刻意的盯守
   * （CLAUDE 默认约定第 6 条、`06 §8` 第 28 条）。
   */
  @Select(
      "SELECT " + COLUMNS + " FROM score WHERE sheet_id = #{sheetId} ORDER BY indicator_id FOR UPDATE")
  List<Score> findValuesForUpdate(@Param("sheetId") long sheetId);

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

  /** 单个评估单的全部评分记录（T3 定稿要按维度组装原始值）。 */
  @Select(
      "SELECT sc.id, sc.sheet_id, sc.indicator_id, sc.raw_value, sc.score_value, sc.source, sc.import_batch"
          + " FROM score sc JOIN sheet s ON s.id = sc.sheet_id"
          + " WHERE s.task_supplier_id = #{taskSupplierId}")
  List<Score> findByTaskSupplierId(@Param("taskSupplierId") long taskSupplierId);

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

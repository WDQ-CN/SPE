package com.spe.context.evaluation.internal.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `eval_task` 的 Mapper（属主上下文内可见，05 §8.3）。
 *
 * <p><b>状态迁移一律带前置条件</b>（06 §2.4 / ADR-5）：发布是
 * `WHERE id=? AND status='DRAFT' AND version=?` 一条语句完成判断与迁移；不提供"查出实体改字段再
 * updateById"的全字段覆盖写法。
 */
@Mapper
public interface EvalTaskMapper extends BaseMapper<EvalTask> {

  String COLUMNS =
      "id, name, year, quarter, template_version_id, deadline, status, version";

  @Select("SELECT " + COLUMNS + " FROM eval_task WHERE id = #{id}")
  Optional<EvalTask> findById(@Param("id") long id);

  /**
   * 编辑草稿（守卫 UPDATE）：状态必须是 DRAFT、且 {@code version} 未被别人改过。
   *
   * <p>0 行有两种含义——并发改动（3001）或状态已不是草稿（2020）：调用方用 {@link #findById} 区分，
   * 见 `TaskService.updateDraft`。
   */
  @Update(
      """
      UPDATE eval_task
      SET name = #{name}, year = #{year}, quarter = #{quarter},
          template_version_id = #{templateVersionId}, deadline = #{deadline}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT' AND version = #{version}
      """)
  int updateDraft(
      @Param("id") long id,
      @Param("version") int version,
      @Param("name") String name,
      @Param("year") int year,
      @Param("quarter") int quarter,
      @Param("templateVersionId") long templateVersionId,
      @Param("deadline") LocalDate deadline);

  /** 列表分页（13 §E18 的 GET /api/tasks）：名称包含匹配 + 可选状态；ORDER BY id DESC（新任务在前）。 */
  @Select(
      """
      <script>
      SELECT id, name, year, quarter, template_version_id, deadline, status, version
      FROM eval_task
      <where>
        <if test="status != null"> AND status = #{status} </if>
        <if test="keyword != null and keyword != ''"> AND name LIKE CONCAT('%', #{keyword}, '%') </if>
      </where>
      ORDER BY id DESC LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<EvalTask> findPage(
      @Param("status") String status,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>
      SELECT COUNT(*) FROM eval_task
      <where>
        <if test="status != null"> AND status = #{status} </if>
        <if test="keyword != null and keyword != ''"> AND name LIKE CONCAT('%', #{keyword}, '%') </if>
      </where>
      </script>
      """)
  long countByFilter(@Param("status") String status, @Param("keyword") String keyword);

  /** 发布（T1）：DRAFT → IN_PROGRESS，0 行 → 3001/2020（同上）。 */
  @Update(
      "UPDATE eval_task SET status = 'IN_PROGRESS', version = version + 1"
          + " WHERE id = #{id} AND status = 'DRAFT' AND version = #{version}")
  int publish(@Param("id") long id, @Param("version") int version);

  /**
   * 发布事务的**第一条语句**（01 J4 / 13 §E10 预留的升级分支落地；03 §38 的 P1-15 根治）：按主键升序
   * 一次性锁住 `eval_task` 的所有行，并把这份结果当作"此刻的唯一事实"返回——`status`/`version` 的守卫
   * 判定与"同周期兄弟任务"的清单都从这里出，不再另起一条读。
   *
   * <p><b>为什么是整表，而不是"同周期那几行"</b>：周期值（`year`/`quarter`）只能从自己那一行拿到，
   * 而任何"先读自己、再锁别人"的写法都躲不掉两选一——先 `FOR UPDATE` 锁自己再去 `FOR SHARE` 锁兄弟，
   * 两个发布交叉时构成 X↔S 互等（**实测 `DeadlockLoserDataAccessException`**，就是 P1-15）；先普通读探出
   * 周期再去锁，则 read view 建在取锁之前，正是 04-056/057/076/096 那一族。只有"一条语句、全局同一个
   * 加锁顺序"能同时避开两者。
   *
   * <p><b>这没有放宽锁的范围</b>：`eval_task` 只有 `(template_version_id)` 与 `(status, deadline)` 两个
   * 二级索引（`V11__evaluation.sql:38-39`），**没有 `(year, quarter)`** ⇒ 原先那条带周期条件的
   * `FOR SHARE` 本来就走聚簇索引全扫，REPEATABLE READ 下连不匹配的行也照样持锁。换成 `FOR UPDATE`
   * 改的是**模式**（S→X），锁集一模一样；真正的变化是"同周期的两个发布由'可能死锁'变成'确定排队'"。
   *
   * <p>⚠️ **与 `04-076`"不写那行就取 S"的有意偏差**：兄弟任务的行本次事务并不写，但这里必须取 X——
   * 无索引时锁集已经等于全表，模式是这个方案里**唯一**还能表达互斥的东西，取 S 等于维持 P1-15 现状
   * （变异实测：改成 `FOR SHARE` 后"第二个发布确实停在锁上"那条判据当场红）。
   *
   * <p>代价（记进 `11 §9.1` 与 `13 §E10`）：这条扫描留下的 next-key 锁会在发布期间挡住 `createDraft`
   * 的 INSERT ⇒ **发布期间新建草稿要排队**。⚠️ 实测这是 S 与 X **共有**的行为（换成 FOR SHARE 后插入
   * 照样被挡），所以它不是"改模式"新增的账，而是这条全表扫描本来就在付的账。排队由
   * `TaskDomainIntegrationTest.draftCreationWaitsInsteadOfFailingWhilePublishHoldsTheLock` 钉住——
   * 那条用例证的不是"会排队"，而是"排队之后照样正常落库，不会被翻成 3001"。
   *
   * <p>⚠️ **投影保持 `COLUMNS` 全列**：调用方要靠同一份结果判 `status`/`version`/`name`/`year`/`quarter`，
   * 裁掉几列就会有人回头补一条普通读把当前读降级成快照读（04-057 的形态）。
   *
   * <p><b>何时重判</b>：`eval_task` 行数上到千级时加一个 `(year, quarter)` 索引（需要新迁移），把扫描换成
   * "按该索引升序 + 回表"，锁集就能从全表收到同周期而顺序不变。
   */
  @Select("SELECT " + COLUMNS + " FROM eval_task ORDER BY id FOR UPDATE")
  List<EvalTask> lockAllTasksByIdAsc();

  /**
   * 任务自动完成（01 J3 / 15 §E6）：所有评估单都 `EFFECTIVE` 时 IN_PROGRESS → COMPLETED。
   *
   * <p>一条自带条件的 UPDATE 完成"判断 + 迁移"（CLAUDE 默认约定第 6 条）：`NOT EXISTS` 读的是本事务内
   * 刚变成 EFFECTIVE 的那一行，不会与前面的行锁成环。影响 0 行是**正常**的（还有未生效的评估单，
   * 或任务已完成）——调用方只把它记进审计 detail，不抛冲突。
   */
  @Update(
      """
      UPDATE eval_task t SET t.status = 'COMPLETED', t.version = t.version + 1
      WHERE t.id = #{id} AND t.status = 'IN_PROGRESS'
        AND NOT EXISTS (
          SELECT 1 FROM task_supplier ts WHERE ts.task_id = t.id AND ts.status <> 'EFFECTIVE'
        )
      """)
  int completeIfAllEffective(@Param("id") long id);

  /**
   * 解锁重评把已完成任务拉回进行中（R5；M5 W3）：守卫 UPDATE，任务本来就是 IN_PROGRESS 时 0 行属正常。
   */
  @Update(
      "UPDATE eval_task SET status = 'IN_PROGRESS', version = version + 1"
          + " WHERE id = #{id} AND status = 'COMPLETED'")
  int reopenIfCompleted(@Param("id") long id);
}

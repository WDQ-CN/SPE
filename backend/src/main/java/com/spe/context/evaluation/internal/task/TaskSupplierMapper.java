package com.spe.context.evaluation.internal.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `task_supplier` 的 Mapper。
 *
 * <p>插入用**批处理**（13 §W3.2 的硬约束）：一个任务勾选几十个供应商时，逐条 INSERT 会变成几十次往返；
 * 这里是"一条 INSERT 多组 VALUES"，由调用方在同一个事务里一次提交。
 */
@Mapper
public interface TaskSupplierMapper extends BaseMapper<TaskSupplier> {

  String COLUMNS = "id, task_id, supplier_id, status, current_node, total_score, total_grade, version";

  /** 发布时批量生成评估单（01 J2）。冲突（同任务同供应商）由 uk_task_supplier 兜底 → 服务层翻 3001。 */
  @Insert(
      """
      <script>
      INSERT INTO task_supplier (task_id, supplier_id, status, current_node, version) VALUES
      <foreach item="item" collection="items" separator=",">
        (#{item.taskId}, #{item.supplierId}, 'SCORING', 0, 0)
      </foreach>
      </script>
      """)
  @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
  int insertBatch(@Param("items") List<TaskSupplier> items);

  @Select("SELECT " + COLUMNS + " FROM task_supplier WHERE id = #{id}")
  Optional<TaskSupplier> findById(@Param("id") long id);

  /**
   * **当前读并锁住评估单行** —— 提交（T2）的**串行化点**（13 §E9 / 04-076 的教训）。
   *
   * <p>为什么必须有它：提交要先"标记本张评分单已提交"，再判断"是不是全都提交了"，最后迁移评估单状态。
   * 若两个提交事务各自先锁住**自己的**评分单行、再去读对方的行（`FOR SHARE`），就会形成
   * "X(自己的行) 等 S(对方的行) ↔ X(对方的行) 等 S(自己的行)" 的**死锁**——本地实测就是这么红的
   * （`DeadlockLoserDataAccessException`）。所以取锁顺序固定为：**先评估单行、再评分单行**，
   * 同一评估单的提交因此被完全串行化，且各自读到的都是当前值。
   */
  @Select("SELECT " + COLUMNS + " FROM task_supplier WHERE id = #{id} FOR UPDATE")
  Optional<TaskSupplier> lockById(@Param("id") long id);

  /** 草稿改选供应商时先清空再重建：此时还没有 sheet（sheet 由发布生成），所以删是安全的。 */
  @Delete("DELETE FROM task_supplier WHERE task_id = #{taskId}")
  int deleteByTaskId(@Param("taskId") long taskId);

  @Select("SELECT " + COLUMNS + " FROM task_supplier WHERE task_id = #{taskId} ORDER BY id")
  List<TaskSupplier> findByTaskId(@Param("taskId") long taskId);

  /**
   * 提交的收敛点（05 §5.5 T2 / 13 §E9 第 5 步）：最后一张评分单提交时把评估单迁到「待审批」。
   *
   * <p>`WHERE status='SCORING' AND version=?`：0 行 ⇒ **必须抛 3001 并整体回滚**（另一个提交事务已经
   * 迁移过了）。静默放过会留下"评估单已进待审批、而本张评分单的提交被吞掉"的错觉（13 §E9）。
   */
  @Update(
      "UPDATE task_supplier SET status = 'PENDING_APPROVAL', current_node = 1, version = version + 1"
          + " WHERE id = #{id} AND status = 'SCORING' AND version = #{version}")
  int markPendingApproval(@Param("id") long id, @Param("version") int version);

  /**
   * 进度看板的**一条聚合查询**（13 §E14）：每个供应商一行，附带"未指派/未提交评分单数"两个计数。
   *
   * <p>用 LEFT JOIN + GROUP BY 而不是逐供应商子查询：供应商数一变，子查询写法会退化成 N 次扫描；
   * 这条语句的代价与供应商数无关（同一张 sheet 表一次扫完），E14 的"不做逐供应商回查"就落在这里。
   */
  @Select(
      """
      SELECT ts.id AS task_supplier_id, ts.supplier_id, ts.status, ts.current_node,
             SUM(CASE WHEN s.id IS NOT NULL AND s.assignee_id IS NULL THEN 1 ELSE 0 END) AS unassigned_sheets,
             SUM(CASE WHEN s.status <> 'SUBMITTED' THEN 1 ELSE 0 END) AS unsubmitted_sheets
      FROM task_supplier ts
      LEFT JOIN sheet s ON s.task_supplier_id = ts.id
      WHERE ts.task_id = #{taskId}
      GROUP BY ts.id, ts.supplier_id, ts.status, ts.current_node
      ORDER BY ts.id
      """)
  List<TaskProgressRow> findProgressRows(@Param("taskId") long taskId);

  @Select("SELECT COUNT(*) FROM task_supplier WHERE task_id = #{taskId}")
  long countByTaskId(@Param("taskId") long taskId);

  /**
   * 01 J4 的判定查询：这批任务里，哪些供应商**已经被用过**。
   *
   * <p>必须在 {@link EvalTaskMapper#lockOtherTaskIdsInPeriod} **之后**执行（先锁定读、再普通读）：
   * read view 由第一条一致性读建立，锁定读不算——所以这条普通读看到的是**此刻已提交**的数据，
   * 而不是事务开始时的快照（CLAUDE 默认约定第 6 条）。
   */
  @Select(
      """
      <script>
      SELECT DISTINCT ts.supplier_id FROM task_supplier ts
      WHERE ts.task_id IN
      <foreach item="item" collection="taskIds" open="(" separator="," close=")">#{item}</foreach>
      AND ts.supplier_id IN
      <foreach item="item" collection="supplierIds" open="(" separator="," close=")">#{item}</foreach>
      </script>
      """)
  List<Long> findUsedSupplierIds(
      @Param("taskIds") Collection<Long> taskIds, @Param("supplierIds") Collection<Long> supplierIds);
}

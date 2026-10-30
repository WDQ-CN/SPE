package com.spe.context.evaluation.internal.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.evaluation.internal.approval.ApprovalTodoRow;
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

  String COLUMNS =
      "id, task_id, supplier_id, status, current_node, round, total_score, total_grade, version";

  /** 发布时批量生成评估单（01 J2）。冲突（同任务同供应商）由 uk_task_supplier 兜底 → 服务层翻 3001。 */
  @Insert(
      """
      <script>
      INSERT INTO task_supplier (task_id, supplier_id, status, current_node, round, version) VALUES
      <foreach item="item" collection="items" separator=",">
        (#{item.taskId}, #{item.supplierId}, 'SCORING', 0, 1, 0)
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

  /** 开放 API active 列表批量取评估单，避免逐任务 N+1（M6 W2）。不要用文本块拼接列清单（04-113）。 */
  @Select(
      "<script>SELECT "
          + COLUMNS
          + " FROM task_supplier WHERE task_id IN"
          + " <foreach item=\"item\" collection=\"taskIds\" open=\"(\" separator=\",\" close=\")\">#{item}</foreach>"
          + " ORDER BY task_id, id</script>")
  List<TaskSupplier> findByTaskIds(@Param("taskIds") java.util.Collection<Long> taskIds);

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
   * 非末级通过（T5，15 §E5）：状态进 `APPROVING`、节点 +1。
   *
   * <p>守卫条件同时钉住「当前节点」与「版本」：0 行 ⇒ 节点已流转 / 版本不符 ⇒ 3001。
   * 末级通过**不走这里**——那一步交给 T3 的 `markEffective`（不先置 EFFECTIVE，否则 T3 的守卫
   * 永远 0 行，冲突检测形同虚设）。
   */
  @Update(
      "UPDATE task_supplier SET status = 'APPROVING', current_node = current_node + 1,"
          + " version = version + 1"
          + " WHERE id = #{id} AND status IN ('PENDING_APPROVAL','APPROVING')"
          + " AND current_node = #{currentNode} AND version = #{version}")
  int markApproving(
      @Param("id") long id, @Param("currentNode") int currentNode, @Param("version") int version);

  /**
   * 驳回（T5，15 §E5）：回 `SCORING`、节点清零、**轮次 +1**，三件事写在同一条语句里。
   *
   * <p>`round = round + 1` 必须在同一条 UPDATE：`uk_approval_record(task_supplier_id, round, seq)`
   * 要求重提后同一节点能再写一条记录，轮次不递增就会撞唯一键（沙盒 V5 已证）。
   */
  @Update(
      "UPDATE task_supplier SET status = 'SCORING', current_node = 0, round = round + 1,"
          + " version = version + 1"
          + " WHERE id = #{id} AND status IN ('PENDING_APPROVAL','APPROVING')"
          + " AND current_node = #{currentNode} AND version = #{version}")
  int markRejected(
      @Param("id") long id, @Param("currentNode") int currentNode, @Param("version") int version);

  /**
   * 解锁重评（R5 / T4；M5 W3）：EFFECTIVE → SCORING，节点清零、轮次 +1、撤销总分/等级。
   *
   * <p>`round+1` 必须与状态迁移写在同一条语句：`uk_approval_record(task_supplier_id, round, seq)`
   * 要求重审记录不撞已有效轮次（与驳回同一条理由）。守卫同时钉住 EFFECTIVE 与版本；0 行由调用方
   * 翻成 3001（并发）或 2020（状态，已在锁内先判）。
   */
  @Update(
      "UPDATE task_supplier SET status = 'SCORING', current_node = 0, round = round + 1,"
          + " total_score = NULL, total_grade = NULL, version = version + 1"
          + " WHERE id = #{id} AND status = 'EFFECTIVE' AND version = #{version}")
  int unlockEffective(@Param("id") long id, @Param("version") int version);

  /**
   * 定稿（T3，15 §E6）：末级的守卫迁移 + 总分/总等级一次写入。
   *
   * <p>守卫里的 `current_node` = **末级节点序号**（调用方已确认这是末级）；`version` 是请求带来的版本
   * （末级通过不先改版本，所以它仍与库里一致）。0 行 ⇒ 3001。
   */
  @Update(
      "UPDATE task_supplier SET status = 'EFFECTIVE', total_score = #{totalScore},"
          + " total_grade = #{totalGrade}, version = version + 1"
          + " WHERE id = #{id} AND status IN ('PENDING_APPROVAL','APPROVING')"
          + " AND current_node = #{currentNode} AND version = #{version}")
  int markEffective(
      @Param("id") long id,
      @Param("currentNode") int currentNode,
      @Param("version") int version,
      @Param("totalScore") java.math.BigDecimal totalScore,
      @Param("totalGrade") String totalGrade);

  /**
   * 「我的待办（审批）」：当前节点是我、且状态在审批中（15 §E8）。
   *
   * <p>驱动侧走 `idx_ts_status_node(status, current_node)`（V13 为它补的索引，15 §E17），
   * `approval_node` 侧走 `idx_approval_node_approver`。只 join 本上下文的表；供应商名称由 Service
   * 经门面批量补齐（cross-context 规则，05 §4.3）。
   */
  @Select(
      """
      SELECT ts.id AS task_supplier_id, ts.task_id, ts.supplier_id, ts.status, ts.current_node,
             ts.round, ts.version, t.name AS task_name, t.year, t.quarter, t.deadline
      FROM task_supplier ts
      JOIN approval_node n ON n.task_id = ts.task_id AND n.seq = ts.current_node
      JOIN eval_task t ON t.id = ts.task_id
      WHERE n.approver_id = #{approverId}
        AND ts.status IN ('PENDING_APPROVAL','APPROVING')
      ORDER BY t.deadline, ts.id
      LIMIT #{size} OFFSET #{offset}
      """)
  List<ApprovalTodoRow> findMyApprovals(
      @Param("approverId") long approverId,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      SELECT COUNT(*)
      FROM task_supplier ts
      JOIN approval_node n ON n.task_id = ts.task_id AND n.seq = ts.current_node
      WHERE n.approver_id = #{approverId}
        AND ts.status IN ('PENDING_APPROVAL','APPROVING')
      """)
  long countMyApprovals(@Param("approverId") long approverId);

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
   * <p>⚠️ 这条**必须是当前读**（`FOR SHARE`）。它原先靠"排在
   * {@link EvalTaskMapper#lockAllTasksByIdAsc()} 之后"拿到当前语义，但那个前提在调用方被破坏了：
   * `TaskService.publish()` 在此之前先做了两条普通读，read view 早在取锁之前就钉好了
   * （`03 §38` 的 P1-2）——表现是"同周期同一供应商的两个任务都能发布成功"，而库里没有任何键拦得住。
   *
   * <p>取 S 而不是 X：本事务不写**别人**的 `task_supplier` 行 ⇒ 不构成 S→X 升级
   * （`04-076` 正是为这个形态付过代价：为修当前读选了 `FOR SHARE`，同事务后面要写 ⇒ 用例确定性变红）。
   */
  @Select(
      """
      <script>
      SELECT DISTINCT ts.supplier_id FROM task_supplier ts
      WHERE ts.task_id IN
      <foreach item="item" collection="taskIds" open="(" separator="," close=")">#{item}</foreach>
      AND ts.supplier_id IN
      <foreach item="item" collection="supplierIds" open="(" separator="," close=")">#{item}</foreach>
      FOR SHARE
      </script>
      """)
  List<Long> findUsedSupplierIds(
      @Param("taskIds") Collection<Long> taskIds, @Param("supplierIds") Collection<Long> supplierIds);
}

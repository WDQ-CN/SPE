package com.spe.context.evaluation.internal.approval;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code approval_record} 的 Mapper（V13）。
 *
 * <p>T5 的写路径（15 §E5 的守卫 UPDATE + 记录追加）随 W3 落地；现在只有**追加与读取**两类。
 *
 * <p>⚠️ **刻意不继承 `BaseMapper`**（03 §38 的 P2-3）：表是只增型（02 §3.3），而 {@code BaseMapper}
 * 会带来 {@code updateById}/{@code deleteById}——到那时"不提供任何 UPDATE/DELETE"就只是注释里的愿望。
 * 这条结构约束由 ArchUnit R-I 钉住（去掉 {@code extends} 的守卫 ⇒ 规则红）。
 */
@Mapper
public interface ApprovalRecordMapper {

  /** 含 {@code created_at}/{@code updated_at}：时间线要按轮与序号排，但也得能把时间读回给前端（03 §38 存疑 13）。 */
  String COLUMNS = "id, task_supplier_id, round, seq, approver_id, action, comment, created_at, updated_at";

  /** 批量追加（同一 T5 事务里只写一条；批处理为 W2/W3 的批量场景备好形态）。 */
  @Insert(
      """
      <script>
      INSERT INTO approval_record (task_supplier_id, round, seq, approver_id, action, comment) VALUES
      <foreach item="item" collection="items" separator=",">
        (#{item.taskSupplierId}, #{item.round}, #{item.seq}, #{item.approverId}, #{item.action}, #{item.comment})
      </foreach>
      </script>
      """)
  int insertBatch(@Param("items") List<ApprovalRecord> items);

  /** 时间线读取：按 (round, seq) 排序 —— 不是 created_at（同一毫秒内顺序不稳，15 §E14）。 */
  @Select("SELECT " + COLUMNS + " FROM approval_record WHERE task_supplier_id = #{taskSupplierId} ORDER BY round, seq")
  List<ApprovalRecord> findByTaskSupplier(@Param("taskSupplierId") long taskSupplierId);

  /**
   * 上一轮（不含当前轮）是否已有末级 APPROVE。定稿事件用它判定「本期首次生效」：
   * 驳回重提时上一轮不会有末级通过，仍算首次；解锁重评再定稿时上一轮已有末级通过，不再触发趋势。
   *
   * <p>当前轮的末级通过记录由 {@code ApprovalService} 在调用定稿**之前**写入，故必须按 {@code round <}
   * 排除当前轮，否则每次定稿都会被判成「非首次」。
   */
  @Select(
      "SELECT COUNT(*) FROM approval_record"
          + " WHERE task_supplier_id = #{taskSupplierId} AND seq = #{seq}"
          + " AND action = 'APPROVE' AND round < #{round}")
  long countEarlierLastNodeApprovals(
      @Param("taskSupplierId") long taskSupplierId,
      @Param("seq") int seq,
      @Param("round") int round);
}

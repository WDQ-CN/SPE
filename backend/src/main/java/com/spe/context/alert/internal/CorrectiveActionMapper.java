package com.spe.context.alert.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 整改任务的读写 Mapper（V1004；29 §3）。
 *
 * <p>写路径的状态迁移一律由 Service 使用带前置状态/version 的 UPDATE，本 Mapper 只提供读查询与
 * 两处明确的批量动作：D5 的下期候选、D7 的 supersede 批量取消。
 */
@Mapper
public interface CorrectiveActionMapper extends BaseMapper<CorrectiveAction> {

  @Select("SELECT * FROM corrective_action WHERE alert_id = #{alertId}")
  CorrectiveAction findByAlertId(@Param("alertId") long alertId);

  /**
   * D5：按“同供应商、周期严格晚于基线、EFFECTIVE”返回全部候选，由验证器判断最早周期与重复周期。
   *
   * <p>不在这里 `LIMIT 1`：J4 没有 `(供应商, 周期)` 唯一约束，同一周期可能有多份生效评估；
   * 静默取第一份会让 `verify_score` 指向错误评估单。允许跳期（例如 Q2 基线、Q4 才再次生效）。
   */
  @Select(
      """
      SELECT ts.id AS task_supplier_id, t.year, t.quarter, ts.total_score
      FROM task_supplier ts
      JOIN eval_task t ON t.id = ts.task_id
      WHERE ts.supplier_id = #{supplierId}
        AND ts.status = 'EFFECTIVE'
        AND (t.year > #{year} OR (t.year = #{year} AND t.quarter > #{quarter}))
      ORDER BY t.year, t.quarter, ts.id
      """)
  List<NextEffectivePeriodRow> findEffectivePeriodsAfter(
      @Param("supplierId") long supplierId,
      @Param("year") int year,
      @Param("quarter") int quarter);

  /** 下一期维度分（从定稿快照读取，不读试算）。 */
  @Select(
      "SELECT score FROM task_supplier_dimension_result"
          + " WHERE task_supplier_id = #{taskSupplierId} AND dimension_id = #{dimensionId}")
  BigDecimal findDimensionScore(
      @Param("taskSupplierId") long taskSupplierId, @Param("dimensionId") long dimensionId);

  /** 等待下期验证的整改（只取尚未回填 verify_* 的 WAITING 行）。 */
  @Select(
      "SELECT * FROM corrective_action"
          + " WHERE supplier_id = #{supplierId} AND status = 'WAITING_VERIFICATION'"
          + " AND verify_year IS NULL ORDER BY id")
  List<CorrectiveAction> findWaitingVerificationBySupplier(@Param("supplierId") long supplierId);

  /**
   * 回填下期验证数据。守卫 version/status，避免用户同时编辑/提交时覆盖。
   *
   * <p>重复周期/维度缺失也走这里：`score` 与 `taskSupplierId` 可为 null，`outcome` 用 PENDING，
   * 由经理人工判断（D5/D9）。
   */
  @Update(
      "UPDATE corrective_action SET verify_year = #{verifyYear},"
          + " verify_quarter = #{verifyQuarter}, verify_task_supplier_id = #{verifyTaskSupplierId},"
          + " verify_score = #{verifyScore}, verify_outcome = #{verifyOutcome},"
          + " verify_comment = #{verifyComment}, version = version + 1,"
          + " updated_at = CURRENT_TIMESTAMP(3)"
          + " WHERE id = #{id} AND version = #{version}"
          + " AND status = 'WAITING_VERIFICATION' AND verify_year IS NULL")
  int fillVerification(
      @Param("id") long id,
      @Param("version") int version,
      @Param("verifyYear") Integer verifyYear,
      @Param("verifyQuarter") Integer verifyQuarter,
      @Param("verifyTaskSupplierId") Long verifyTaskSupplierId,
      @Param("verifyScore") BigDecimal verifyScore,
      @Param("verifyOutcome") String verifyOutcome,
      @Param("verifyComment") String verifyComment);

  /** 编辑措施/负责人/截止日期（OPEN/IN_PROGRESS/REOPENED 才可编辑；version 守卫）。 */
  @Update(
      "UPDATE corrective_action SET owner_id = #{ownerId}, due_date = #{dueDate},"
          + " measure = #{measure}, expected_result = #{expectedResult},"
          + " version = version + 1, updated_at = CURRENT_TIMESTAMP(3)"
          + " WHERE id = #{id} AND version = #{version}"
          + " AND status IN ('OPEN', 'IN_PROGRESS', 'REOPENED')")
  int updateEditable(
      @Param("id") long id,
      @Param("ownerId") long ownerId,
      @Param("dueDate") java.time.LocalDate dueDate,
      @Param("measure") String measure,
      @Param("expectedResult") String expectedResult,
      @Param("version") int version);

  @Update(
      "UPDATE corrective_action SET status = 'IN_PROGRESS', version = version + 1,"
          + " updated_at = CURRENT_TIMESTAMP(3)"
          + " WHERE id = #{id} AND version = #{version}"
          + " AND status IN ('OPEN', 'REOPENED')")
  int start(@Param("id") long id, @Param("version") int version);

  @Update(
      "UPDATE corrective_action SET status = 'WAITING_VERIFICATION',"
          + " submit_comment = #{comment}, submitted_at = CURRENT_TIMESTAMP(3),"
          + " version = version + 1, updated_at = CURRENT_TIMESTAMP(3)"
          + " WHERE id = #{id} AND version = #{version} AND status = 'IN_PROGRESS'")
  int submit(
      @Param("id") long id, @Param("comment") String comment, @Param("version") int version);

  @Update(
      "UPDATE corrective_action SET status = #{targetStatus}, verified_by = #{verifiedBy},"
          + " verify_comment = #{comment}, verified_at = CURRENT_TIMESTAMP(3),"
          + " version = version + 1, updated_at = CURRENT_TIMESTAMP(3)"
          + " WHERE id = #{id} AND version = #{version} AND status = 'WAITING_VERIFICATION'")
  int verify(
      @Param("id") long id,
      @Param("targetStatus") String targetStatus,
      @Param("verifiedBy") long verifiedBy,
      @Param("comment") String comment,
      @Param("version") int version);

  @Update(
      "UPDATE corrective_action SET status = 'CANCELED', canceled_reason = #{reason},"
          + " version = version + 1, updated_at = CURRENT_TIMESTAMP(3)"
          + " WHERE id = #{id} AND version = #{version}"
          + " AND status IN ('OPEN', 'IN_PROGRESS', 'WAITING_VERIFICATION', 'REOPENED')")
  int cancel(
      @Param("id") long id, @Param("reason") String reason, @Param("version") int version);

  /** D7 取消前取出将被取消的行，供系统审计逐条记录。 */
  @Select(
      """
      <script>
      SELECT * FROM corrective_action
      WHERE alert_id IN
        <foreach collection="alertIds" item="id" open="(" separator="," close=")">
          #{id}
        </foreach>
        AND status IN ('OPEN', 'IN_PROGRESS', 'WAITING_VERIFICATION', 'REOPENED')
      ORDER BY id
      </script>
      """)
  List<CorrectiveAction> findUnfinishedByAlertIds(@Param("alertIds") List<Long> alertIds);

  /**
   * D7：预警被重算 supersede 时，批量取消未完成整改。
   *
   * <p>只改未完成状态；VERIFIED 的历史保留。每条目 version+1，避免 W2 的详情编辑拿着旧版本继续写。
   */
  @Update(
      """
      <script>
      UPDATE corrective_action
      SET status = 'CANCELED',
          canceled_reason = #{reason},
          version = version + 1,
          updated_at = CURRENT_TIMESTAMP(3)
      WHERE alert_id IN
        <foreach collection="alertIds" item="id" open="(" separator="," close=")">
          #{id}
        </foreach>
        AND status IN ('OPEN', 'IN_PROGRESS', 'WAITING_VERIFICATION', 'REOPENED')
      </script>
      """)
  int cancelUnfinishedByAlertIds(
      @Param("alertIds") List<Long> alertIds, @Param("reason") String reason);
}

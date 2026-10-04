package com.spe.context.evaluation.internal.sheet;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** `sheet` 的 Mapper：发布时批处理生成；改派与"我的待办"（13 §E12/E13）都在这里。 */
@Mapper
public interface SheetMapper extends BaseMapper<Sheet> {

  String COLUMNS = "id, task_supplier_id, dimension_id, assignee_id, status, version";

  @Insert(
      """
      <script>
      INSERT INTO sheet (task_supplier_id, dimension_id, assignee_id, status, version) VALUES
      <foreach item="item" collection="items" separator=",">
        (#{item.taskSupplierId}, #{item.dimensionId},
         <choose><when test="item.assigneeId != null">#{item.assigneeId}</when><otherwise>NULL</otherwise></choose>,
         <choose><when test="item.assigneeId != null">'SCORING'</when><otherwise>'UNASSIGNED'</otherwise></choose>, 0)
      </foreach>
      </script>
      """)
  @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
  int insertBatch(@Param("items") List<Sheet> items);

  @Select("SELECT " + COLUMNS + " FROM sheet WHERE id = #{id}")
  Optional<Sheet> findById(@Param("id") long id);

  /** 当前读并锁住评分单行（改派 / 提交前校验都靠它，CLAUDE 默认约定第 6 条）。 */
  @Select("SELECT " + COLUMNS + " FROM sheet WHERE id = #{id} FOR UPDATE")
  Optional<Sheet> findByIdForUpdate(@Param("id") long id);

  /**
   * 「我的待办」（13 §E13 / 01 E1）：只查指派给我的评分单，按截止日期排序。
   *
   * <p>⚠️ 只 join **本上下文**的三张表（sheet / task_supplier / eval_task）：供应商名与维度名分别属
   * supplier / template 上下文，跨上下文要经对方 api 门面（05 §4.3/§8.3），名称由 Service 批量补齐。
   *
   * <p>过滤条件 `assignee_id = ?` 正对 05 §8.2 为待办准备的 `idx_sheet_assignee_status`
   * （左前缀即 assignee_id）。
   */
  @Select(
      """
      SELECT s.id AS sheet_id, s.task_supplier_id, ts.supplier_id, s.dimension_id,
             t.id AS task_id, t.name AS task_name, t.template_version_id, t.deadline, s.status, s.version
      FROM sheet s
      JOIN task_supplier ts ON ts.id = s.task_supplier_id
      JOIN eval_task t ON t.id = ts.task_id
      WHERE s.assignee_id = #{assigneeId}
      ORDER BY t.deadline, t.id, s.id
      """)
  List<MySheetRow> findMySheets(@Param("assigneeId") long assigneeId);

  /**
   * 评分单的"上下文"（写分与提交都要它）：sheet 自己的状态/负责人 + 所属评估单/任务 + 任务的模板版本。
   *
   * <p>写分路径要靠 `template_version_id` 取**快照**来换算与校验，所以一次查全，避免在写路径上
   * 逐层回查（CLAUDE 审查维度第 3 条：热路径上每请求查库要有理由）。
   */
  @Select(
      """
      SELECT s.id AS sheet_id, s.assignee_id, s.status AS sheet_status, s.task_supplier_id,
             s.dimension_id, s.version AS sheet_version, ts.task_id AS task_id, t.template_version_id
      FROM sheet s
      JOIN task_supplier ts ON ts.id = s.task_supplier_id
      JOIN eval_task t ON t.id = ts.task_id
      WHERE s.id = #{sheetId}
      """)
  java.util.Optional<SheetContextRow> findContextById(@Param("sheetId") long sheetId);

  @Select("SELECT " + COLUMNS + " FROM sheet WHERE task_supplier_id = #{taskSupplierId} ORDER BY id")
  List<Sheet> findByTaskSupplierId(@Param("taskSupplierId") long taskSupplierId);

  /** 任务的评分单总数（任务详情页用）。 */
  @Select(
      "SELECT COUNT(*) FROM sheet s JOIN task_supplier ts ON ts.id = s.task_supplier_id"
          + " WHERE ts.task_id = #{taskId}")
  long countByTaskId(@Param("taskId") long taskId);

  /** 任务里未提交的评分单数（任务详情页与看板同口径）。 */
  @Select(
      "SELECT COUNT(*) FROM sheet s JOIN task_supplier ts ON ts.id = s.task_supplier_id"
          + " WHERE ts.task_id = #{taskId} AND s.status <> 'SUBMITTED'")
  long countUnsubmittedByTaskId(@Param("taskId") long taskId);

  /** 按任务取全部评分单（进度看板要按维度分组试算，一次取回，避免逐供应商回查）。 */
  @Select(
      "SELECT s.id, s.task_supplier_id, s.dimension_id, s.assignee_id, s.status, s.version"
          + " FROM sheet s JOIN task_supplier ts ON ts.id = s.task_supplier_id"
          + " WHERE ts.task_id = #{taskId} ORDER BY s.id")
  List<Sheet> findByTaskId(@Param("taskId") long taskId);

  @Select("SELECT COUNT(*) FROM sheet WHERE task_supplier_id = #{taskSupplierId}")
  long countByTaskSupplierId(@Param("taskSupplierId") long taskSupplierId);

  /**
   * 该任务下**指派给我**的评分单数（04-096 的"参与判据"）：>0 才算参与了这个任务，
   * 才有资格取它的任务级导入模板 / 向它导入。
   *
   * <p>为什么需要它：模板与导入是**任务级**的（01 E3：行 = 供应商、列 = 全部指标），形状上无法只装
   * 一半；但"能不能碰这个任务"必须收紧，否则任意评估人只要枚举 taskId 就能读走别人的供应商清单。
   */
  @Select(
      "SELECT COUNT(*) FROM sheet s JOIN task_supplier ts ON ts.id = s.task_supplier_id"
          + " WHERE ts.task_id = #{taskId} AND s.assignee_id = #{assigneeId}")
  long countMineInTask(@Param("taskId") long taskId, @Param("assigneeId") long assigneeId);

  /** 提交（13 §E9 第 3 步）：评分中 → 已提交，0 行 ⇒ 3001（并发或状态不允许由调用方区分）。 */
  @Update(
      "UPDATE sheet SET status = 'SUBMITTED', version = version + 1"
          + " WHERE id = #{id} AND status = 'SCORING' AND version = #{version}")
  int markSubmitted(@Param("id") long id, @Param("version") int version);

  /**
   * 该评估单里**还没有提交**的评分单（13 §E9 第 4 步 / §E11：这一读必须是**当前读**）。
   *
   * <p>普通读会走事务开始时的快照，读到"别人还没提交"而重复触发评估单迁移，或反之漏迁移。
   * 用 `FOR SHARE` 而不是聚合（`COUNT(*) … FOR SHARE` 不合法）：返回列表，空列表即"全部已提交"。
   */
  @Select(
      "SELECT id FROM sheet WHERE task_supplier_id = #{taskSupplierId} AND id <> #{excludedSheetId}"
          + " AND status <> 'SUBMITTED' ORDER BY id FOR SHARE")
  List<Long> lockOtherUnsubmittedIds(
      @Param("taskSupplierId") long taskSupplierId, @Param("excludedSheetId") long excludedSheetId);

  /**
   * 改派（01 J2 / 13 §E13）：只允许「待指派 / 评分中」，且不改换评分单本身、不清空已有评分记录。
   *
   * <p>守卫写成 `status IN ('UNASSIGNED','SCORING') AND version = ?`：0 行 ⇒ 3001（并发）或 2020/2021
   * 语义由调用方区分。指派后状态置 SCORING——"有主了"（01 §6.4 的状态机语义）。
   */
  @Update(
      "UPDATE sheet SET assignee_id = #{assigneeId}, status = 'SCORING', version = version + 1"
          + " WHERE id = #{id} AND status IN ('UNASSIGNED', 'SCORING') AND version = #{version}")
  int reassign(
      @Param("id") long id, @Param("assigneeId") long assigneeId, @Param("version") int version);
}

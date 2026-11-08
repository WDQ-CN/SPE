package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 工单 Mapper。 */
@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

  String COLUMNS =
      "id, order_no, company_id, plant_id, item_id, bom_id, qty, status, material_cost,"
          + " received_qty, version, created_by, created_at, released_at, completed_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM work_order " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<WorkOrder> findPage(
      @Param("companyId") Long companyId,
      @Param("status") WorkOrderStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM work_order " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId, @Param("status") WorkOrderStatus status);

  @Select("SELECT " + COLUMNS + " FROM work_order WHERE id = #{id}")
  Optional<WorkOrder> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM work_order WHERE id = #{id} FOR UPDATE")
  Optional<WorkOrder> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE work_order SET status = 'RELEASED', released_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'CREATED'
      """)
  int markReleased(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE work_order SET status = 'IN_PROGRESS', version = version + 1
      WHERE id = #{id} AND status = 'RELEASED'
      """)
  int markInProgress(@Param("id") long id);

  @Update(
      """
      UPDATE work_order
      SET material_cost = material_cost + #{amount}, version = version + 1
      WHERE id = #{id}
      """)
  int addMaterialCost(@Param("id") long id, @Param("amount") BigDecimal amount);

  @Update(
      """
      UPDATE work_order
      SET received_qty = received_qty + #{qty},
          status = CASE WHEN received_qty + #{qty} >= qty THEN 'COMPLETED' ELSE status END,
          completed_at = CASE WHEN received_qty + #{qty} >= qty THEN #{now} ELSE completed_at END,
          version = version + 1
      WHERE id = #{id} AND status = 'IN_PROGRESS' AND received_qty + #{qty} <= qty
      """)
  int increaseReceived(
      @Param("id") long id, @Param("qty") BigDecimal qty, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE work_order SET status = 'CLOSED', version = version + 1
      WHERE id = #{id} AND status IN ('COMPLETED','IN_PROGRESS')
      """)
  int markClosed(@Param("id") long id);
}

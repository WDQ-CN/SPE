package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 工单组件 Mapper。 */
@Mapper
public interface WorkOrderComponentMapper extends BaseMapper<WorkOrderComponent> {

  String COLUMNS =
      "id, work_order_id, line_no, bom_line_id, component_item_id, qty_required, uom, issued_qty";

  @Select(
      "SELECT " + COLUMNS + " FROM work_order_component WHERE work_order_id = #{workOrderId}"
          + " ORDER BY line_no")
  List<WorkOrderComponent> findByWorkOrderId(@Param("workOrderId") long workOrderId);

  @Select(
      "SELECT " + COLUMNS + " FROM work_order_component"
          + " WHERE id = #{id} AND work_order_id = #{workOrderId}")
  Optional<WorkOrderComponent> findByIdAndWorkOrder(
      @Param("id") long id, @Param("workOrderId") long workOrderId);

  @Update(
      """
      UPDATE work_order_component SET issued_qty = issued_qty + #{qty}
      WHERE id = #{id} AND work_order_id = #{workOrderId} AND issued_qty + #{qty} <= qty_required
      """)
  int increaseIssued(
      @Param("id") long id,
      @Param("workOrderId") long workOrderId,
      @Param("qty") BigDecimal qty);
}

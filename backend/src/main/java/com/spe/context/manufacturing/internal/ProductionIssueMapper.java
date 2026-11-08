package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 生产领料 Mapper。 */
@Mapper
public interface ProductionIssueMapper extends BaseMapper<ProductionIssue> {

  String COLUMNS =
      "id, work_order_id, component_id, item_id, lot_no, location_id, qty, unit_cost, amount,"
          + " created_by, created_at";

  @Select(
      "SELECT " + COLUMNS + " FROM production_issue WHERE work_order_id = #{workOrderId}"
          + " ORDER BY id")
  List<ProductionIssue> findByWorkOrderId(@Param("workOrderId") long workOrderId);

  @Update(
      """
      UPDATE production_issue SET unit_cost = #{unitCost}, amount = #{amount}
      WHERE id = #{id}
      """)
  int updateCost(
      @Param("id") long id,
      @Param("unitCost") java.math.BigDecimal unitCost,
      @Param("amount") java.math.BigDecimal amount);
}

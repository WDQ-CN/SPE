package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 完工入库 Mapper。 */
@Mapper
public interface ProductionReceiptMapper extends BaseMapper<ProductionReceipt> {

  String COLUMNS =
      "id, receipt_no, work_order_id, warehouse_id, location_id, lot_no, qty, unit_cost, amount,"
          + " status, created_by, created_at";

  @Select(
      "SELECT " + COLUMNS + " FROM production_receipt WHERE work_order_id = #{workOrderId}"
          + " ORDER BY id")
  List<ProductionReceipt> findByWorkOrderId(@Param("workOrderId") long workOrderId);
}

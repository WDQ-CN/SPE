package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 销售发货行 Mapper。 */
@Mapper
public interface SalesShipmentLineMapper extends BaseMapper<SalesShipmentLine> {

  String COLUMNS =
      "id, shipment_id, line_no, order_line_id, item_id, lot_no, location_id, qty,"
          + " cost_unit, cost_amount, returned_qty";

  @Select(
      "SELECT " + COLUMNS + " FROM sales_shipment_line WHERE shipment_id = #{shipmentId}"
          + " ORDER BY line_no")
  List<SalesShipmentLine> findByShipmentId(@Param("shipmentId") long shipmentId);

  @Select(
      "SELECT " + COLUMNS + " FROM sales_shipment_line WHERE id = #{id} AND shipment_id = #{shipmentId}")
  Optional<SalesShipmentLine> findByIdAndShipment(
      @Param("id") long id, @Param("shipmentId") long shipmentId);

  @Update(
      """
      UPDATE sales_shipment_line
      SET cost_unit = #{costUnit}, cost_amount = #{costAmount}
      WHERE id = #{id}
      """)
  int updateCost(
      @Param("id") long id,
      @Param("costUnit") java.math.BigDecimal costUnit,
      @Param("costAmount") java.math.BigDecimal costAmount);

  @Update(
      """
      UPDATE sales_shipment_line
      SET returned_qty = returned_qty + #{qty}
      WHERE id = #{id} AND returned_qty + #{qty} <= qty
      """)
  int increaseReturned(
      @Param("id") long id, @Param("qty") java.math.BigDecimal qty);
}

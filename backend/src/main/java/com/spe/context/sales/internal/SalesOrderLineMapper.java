package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 销售订单行 Mapper。 */
@Mapper
public interface SalesOrderLineMapper extends BaseMapper<SalesOrderLine> {

  String COLUMNS =
      "id, order_id, line_no, item_id, qty, uom, unit_price, discount_rate, tax_rate,"
          + " net_amount, tax_amount, shipped_qty, invoiced_qty";

  @Select("SELECT " + COLUMNS + " FROM sales_order_line WHERE order_id = #{orderId} ORDER BY line_no")
  List<SalesOrderLine> findByOrderId(@Param("orderId") long orderId);

  @Select(
      "SELECT " + COLUMNS + " FROM sales_order_line WHERE id = #{id} AND order_id = #{orderId}")
  Optional<SalesOrderLine> findByIdAndOrder(
      @Param("id") long id, @Param("orderId") long orderId);

  @Update(
      """
      UPDATE sales_order_line
      SET shipped_qty = shipped_qty + #{qty}
      WHERE id = #{id} AND order_id = #{orderId} AND shipped_qty + #{qty} <= qty
      """)
  int increaseShipped(
      @Param("id") long id,
      @Param("orderId") long orderId,
      @Param("qty") java.math.BigDecimal qty);

  @Update(
      """
      UPDATE sales_order_line
      SET invoiced_qty = invoiced_qty + #{qty}
      WHERE id = #{id} AND order_id = #{orderId}
        AND invoiced_qty + #{qty} <= shipped_qty
      """)
  int increaseInvoiced(
      @Param("id") long id,
      @Param("orderId") long orderId,
      @Param("qty") java.math.BigDecimal qty);
}

package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 销售退货行 Mapper。 */
@Mapper
public interface SalesReturnLineMapper extends BaseMapper<SalesReturnLine> {

  String COLUMNS =
      "id, return_id, line_no, shipment_line_id, order_line_id, item_id, qty, unit_price,"
          + " discount_rate, tax_rate, net_amount, tax_amount, reason";

  @Select(
      "SELECT " + COLUMNS + " FROM sales_return_line WHERE return_id = #{returnId} ORDER BY line_no")
  List<SalesReturnLine> findByReturnId(@Param("returnId") long returnId);
}

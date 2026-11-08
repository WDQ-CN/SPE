package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 销售发票行 Mapper。 */
@Mapper
public interface SalesInvoiceLineMapper extends BaseMapper<SalesInvoiceLine> {

  String COLUMNS =
      "id, invoice_id, line_no, order_line_id, shipment_line_id, item_id, qty, unit_price,"
          + " discount_rate, tax_rate, net_amount, tax_amount";

  @Select(
      "SELECT " + COLUMNS + " FROM sales_invoice_line WHERE invoice_id = #{invoiceId}"
          + " ORDER BY line_no")
  List<SalesInvoiceLine> findByInvoiceId(@Param("invoiceId") long invoiceId);
}

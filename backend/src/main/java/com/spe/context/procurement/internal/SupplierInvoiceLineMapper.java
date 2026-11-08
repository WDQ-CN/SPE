package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 供应商发票行 Mapper。 */
@Mapper
public interface SupplierInvoiceLineMapper extends BaseMapper<SupplierInvoiceLine> {

  String COLUMNS =
      "id, invoice_id, line_no, po_line_id, item_id, qty, unit_price, amount, tax_amount";

  @Select("SELECT " + COLUMNS + " FROM supplier_invoice_line WHERE invoice_id = #{invoiceId}"
      + " ORDER BY line_no")
  List<SupplierInvoiceLine> findByInvoiceId(@Param("invoiceId") long invoiceId);
}

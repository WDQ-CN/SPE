package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 销售报价行 Mapper。 */
@Mapper
public interface SalesQuoteLineMapper extends BaseMapper<SalesQuoteLine> {

  String COLUMNS =
      "id, quote_id, line_no, item_id, qty, uom, unit_price, discount_rate, tax_rate,"
          + " net_amount, tax_amount";

  @Select("SELECT " + COLUMNS + " FROM sales_quote_line WHERE quote_id = #{quoteId} ORDER BY line_no")
  List<SalesQuoteLine> findByQuoteId(@Param("quoteId") long quoteId);
}

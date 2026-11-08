package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 供应商报价行 Mapper。 */
@Mapper
public interface SupplierQuoteLineMapper extends BaseMapper<SupplierQuoteLine> {

  String COLUMNS =
      "id, quote_id, rfq_line_id, unit_price, tax_rate, tax_amount, lead_time_days, moq,"
          + " qty_available, incoterm, remark";

  @Select(
      "SELECT " + COLUMNS + " FROM supplier_quote_line WHERE quote_id = #{quoteId}"
          + " ORDER BY rfq_line_id")
  List<SupplierQuoteLine> findByQuoteId(@Param("quoteId") long quoteId);

  @Select("SELECT " + COLUMNS + " FROM supplier_quote_line WHERE id = #{id}")
  Optional<SupplierQuoteLine> findById(@Param("id") long id);

  @Delete("DELETE FROM supplier_quote_line WHERE quote_id = #{quoteId}")
  int deleteByQuoteId(@Param("quoteId") long quoteId);
}

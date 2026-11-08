package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 供应商报价 Mapper。 */
@Mapper
public interface SupplierQuoteMapper extends BaseMapper<SupplierQuote> {

  String COLUMNS =
      "id, quote_no, rfq_id, supplier_id, currency, valid_until, status, submitted_at, version,"
          + " created_by, created_at";

  @Select("SELECT " + COLUMNS + " FROM supplier_quote WHERE id = #{id}")
  Optional<SupplierQuote> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM supplier_quote WHERE id = #{id} FOR UPDATE")
  Optional<SupplierQuote> lockById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM supplier_quote WHERE rfq_id = #{rfqId}"
          + " AND status <> 'WITHDRAWN' ORDER BY id")
  List<SupplierQuote> findByRfqId(@Param("rfqId") long rfqId);

  @Select(
      "SELECT " + COLUMNS + " FROM supplier_quote"
          + " WHERE rfq_id = #{rfqId} AND supplier_id = #{supplierId}")
  Optional<SupplierQuote> findByRfqAndSupplier(
      @Param("rfqId") long rfqId, @Param("supplierId") long supplierId);

  @Update(
      """
      UPDATE supplier_quote
      SET currency = #{currency}, valid_until = #{validUntil}, status = 'SUBMITTED',
          submitted_at = NOW(3), version = version + 1
      WHERE id = #{id}
      """)
  int markSubmitted(
      @Param("id") long id,
      @Param("currency") String currency,
      @Param("validUntil") java.time.LocalDate validUntil);

  @Update(
      """
      UPDATE supplier_quote SET status = 'WITHDRAWN', version = version + 1
      WHERE id = #{id} AND status = 'SUBMITTED'
      """)
  int markWithdrawn(@Param("id") long id);
}

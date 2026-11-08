package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 销售报价 Mapper。 */
@Mapper
public interface SalesQuoteMapper extends BaseMapper<SalesQuote> {

  String COLUMNS =
      "id, quote_no, company_id, customer_id, currency, valid_until, status, net_amount,"
          + " tax_amount, total_amount, version, created_by, created_at, sent_at, accepted_at,"
          + " converted_order_id";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="customerId != null">AND customer_id = #{customerId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM sales_quote " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<SalesQuote> findPage(
      @Param("companyId") Long companyId,
      @Param("customerId") Long customerId,
      @Param("status") SalesQuoteStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM sales_quote " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("customerId") Long customerId,
      @Param("status") SalesQuoteStatus status);

  @Select("SELECT " + COLUMNS + " FROM sales_quote WHERE id = #{id}")
  Optional<SalesQuote> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM sales_quote WHERE id = #{id} FOR UPDATE")
  Optional<SalesQuote> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE sales_quote SET status = 'SENT', sent_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markSent(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE sales_quote
      SET status = 'ACCEPTED', accepted_at = #{now}, converted_order_id = #{orderId},
          version = version + 1
      WHERE id = #{id} AND status = 'SENT'
      """)
  int markAccepted(
      @Param("id") long id, @Param("orderId") long orderId, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE sales_quote SET status = 'EXPIRED', version = version + 1
      WHERE id = #{id} AND status IN ('DRAFT','SENT')
      """)
  int markExpired(@Param("id") long id);
}

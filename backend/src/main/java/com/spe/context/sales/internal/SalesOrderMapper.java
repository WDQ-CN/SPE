package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 销售订单 Mapper。 */
@Mapper
public interface SalesOrderMapper extends BaseMapper<SalesOrder> {

  String COLUMNS =
      "id, order_no, company_id, customer_id, source_quote_id, currency, payment_terms, status,"
          + " net_amount, tax_amount, total_amount, version, created_by, created_at,"
          + " confirmed_by, confirmed_at, canceled_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="customerId != null">AND customer_id = #{customerId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM sales_order " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<SalesOrder> findPage(
      @Param("companyId") Long companyId,
      @Param("customerId") Long customerId,
      @Param("status") SalesOrderStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM sales_order " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("customerId") Long customerId,
      @Param("status") SalesOrderStatus status);

  @Select("SELECT " + COLUMNS + " FROM sales_order WHERE id = #{id}")
  Optional<SalesOrder> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM sales_order WHERE id = #{id} FOR UPDATE")
  Optional<SalesOrder> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE sales_order
      SET status = 'CONFIRMED', confirmed_by = #{operator}, confirmed_at = #{now},
          version = version + 1
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markConfirmed(
      @Param("id") long id, @Param("operator") long operator, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE sales_order SET status = 'CANCELED', canceled_at = #{now}, version = version + 1
      WHERE id = #{id} AND status IN ('DRAFT','CONFIRMED')
      """)
  int markCanceled(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE sales_order SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status IN ('CONFIRMED','PARTIAL_SHIPPED')
      """)
  int markShippingStatus(
      @Param("id") long id, @Param("target") SalesOrderStatus target);
}

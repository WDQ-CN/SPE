package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 销售发票 Mapper。 */
@Mapper
public interface SalesInvoiceMapper extends BaseMapper<SalesInvoice> {

  String COLUMNS =
      "id, invoice_no, company_id, customer_id, order_id, currency, status, net_amount,"
          + " tax_amount, total_amount, paid_amount, issued_at, due_date, remark, version,"
          + " created_by, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="customerId != null">AND customer_id = #{customerId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM sales_invoice " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<SalesInvoice> findPage(
      @Param("companyId") Long companyId,
      @Param("customerId") Long customerId,
      @Param("status") SalesInvoiceStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM sales_invoice " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("customerId") Long customerId,
      @Param("status") SalesInvoiceStatus status);

  @Select("SELECT " + COLUMNS + " FROM sales_invoice WHERE id = #{id}")
  Optional<SalesInvoice> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM sales_invoice WHERE id = #{id} FOR UPDATE")
  Optional<SalesInvoice> lockById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM sales_invoice"
          + " WHERE company_id = #{companyId} AND status IN ('ISSUED','PARTIAL')"
          + " AND total_amount > paid_amount AND issued_at < #{asOf} ORDER BY due_date, id")
  List<SalesInvoice> findOpen(
      @Param("companyId") long companyId, @Param("asOf") java.time.LocalDateTime asOf);

  @Update(
      """
      UPDATE sales_invoice
      SET paid_amount = paid_amount + #{amount},
          status = CASE WHEN paid_amount + #{amount} >= total_amount THEN 'PAID' ELSE 'PARTIAL' END,
          version = version + 1
      WHERE id = #{id} AND status IN ('ISSUED','PARTIAL')
        AND paid_amount + #{amount} <= total_amount
      """)
  int increasePaid(@Param("id") long id, @Param("amount") BigDecimal amount);

  @Update(
      "UPDATE sales_invoice SET status = 'VOID', version = version + 1"
          + " WHERE id = #{id} AND status = 'ISSUED'")
  int markVoid(@Param("id") long id);
}

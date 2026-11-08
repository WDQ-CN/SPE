package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.procurement.api.SupplierInvoiceStatus;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 供应商发票 Mapper。 */
@Mapper
public interface SupplierInvoiceMapper extends BaseMapper<SupplierInvoice> {

  String COLUMNS =
      "id, doc_no, supplier_invoice_no, company_id, supplier_id, po_id, invoice_date, due_date,"
          + " currency, net_amount, tax_amount, total_amount, paid_amount, status, created_by,"
          + " approved_by, approved_at, version, created_at, updated_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="supplierId != null">AND supplier_id = #{supplierId}</if>
        <if test="status != null">AND status = #{status}</if>
        <if test="keyword != null and keyword != ''">
          AND (doc_no LIKE CONCAT('%', #{keyword}, '%')
               OR supplier_invoice_no LIKE CONCAT('%', #{keyword}, '%'))
        </if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM supplier_invoice " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<SupplierInvoice> findPage(
      @Param("companyId") Long companyId,
      @Param("supplierId") Long supplierId,
      @Param("status") SupplierInvoiceStatus status,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM supplier_invoice " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("supplierId") Long supplierId,
      @Param("status") SupplierInvoiceStatus status,
      @Param("keyword") String keyword);

  @Select("SELECT " + COLUMNS + " FROM supplier_invoice WHERE id = #{id}")
  Optional<SupplierInvoice> findById(@Param("id") long id);

  /** 付款申请创建时锁定发票行：并发申请要在同一把行锁上串行判断未付额度。 */
  @Select("SELECT " + COLUMNS + " FROM supplier_invoice WHERE id = #{id} FOR UPDATE")
  Optional<SupplierInvoice> findByIdForUpdate(@Param("id") long id);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM supplier_invoice WHERE id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + "</script>")
  List<SupplierInvoice> findByIds(@Param("ids") java.util.Collection<Long> ids);

  @Select(
      "SELECT COUNT(*) FROM supplier_invoice WHERE supplier_id = #{supplierId}"
          + " AND supplier_invoice_no = #{invoiceNo}")
  long countBySupplierAndNo(
      @Param("supplierId") long supplierId, @Param("invoiceNo") String invoiceNo);

  @Select(
      "SELECT COUNT(*) FROM supplier_invoice WHERE po_id = #{poId} AND status <> 'CANCELED'")
  long countActiveByPo(@Param("poId") long poId);

  @Select(
      "SELECT COUNT(*) FROM supplier_invoice WHERE po_id = #{poId}"
          + " AND status IN ('APPROVED','PARTIALLY_PAID','PAID')")
  long countApprovedByPo(@Param("poId") long poId);

  @Update(
      """
      UPDATE supplier_invoice SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status IN ('RECEIVED','MATCHED','EXCEPTION') AND version = #{version}
      """)
  int updateMatchStatus(
      @Param("id") long id,
      @Param("target") SupplierInvoiceStatus target,
      @Param("version") int version);

  @Update(
      """
      UPDATE supplier_invoice
      SET status = 'APPROVED', approved_by = #{approverId}, approved_at = CURRENT_TIMESTAMP(3),
          version = version + 1
      WHERE id = #{id} AND status IN ('MATCHED','EXCEPTION') AND created_by <> #{approverId}
        AND version = #{version}
      """)
  int approve(
      @Param("id") long id, @Param("approverId") long approverId, @Param("version") int version);

  /**
   * 付款登记：原子累加已付金额；超过发票总额或状态不是 APPROVED/PARTIALLY_PAID 时影响 0 行。
   *
   * <p>并发下两笔付款不会把发票付超（行锁 + 条件更新），第二笔由服务层翻成 2225。
   */
  @Select(
      "SELECT COALESCE(SUM(total_amount), 0) FROM supplier_invoice"
          + " WHERE company_id = #{companyId} AND status IN ('APPROVED','PARTIALLY_PAID','PAID')"
          + " AND invoice_date <= #{asOf}")
  java.math.BigDecimal sumApprovedTotalAsOf(
      @Param("companyId") long companyId, @Param("asOf") java.time.LocalDate asOf);

  @Select(
      "SELECT COALESCE(SUM(amount), 0) FROM payment_request"
          + " WHERE company_id = #{companyId} AND status = 'PAID'"
          + " AND paid_at < DATE_ADD(#{asOf}, INTERVAL 1 DAY)")
  java.math.BigDecimal sumPaidAsOf(
      @Param("companyId") long companyId, @Param("asOf") java.time.LocalDate asOf);

  @Update(
      """
      UPDATE supplier_invoice
      SET paid_amount = paid_amount + #{amount},
          status = CASE WHEN paid_amount + #{amount} >= total_amount THEN 'PAID' ELSE 'PARTIALLY_PAID' END,
          version = version + 1
      WHERE id = #{id} AND status IN ('APPROVED','PARTIALLY_PAID')
        AND paid_amount + #{amount} <= total_amount
      """)
  int registerPayment(@Param("id") long id, @Param("amount") java.math.BigDecimal amount);
}

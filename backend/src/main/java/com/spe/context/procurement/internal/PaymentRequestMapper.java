package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.procurement.api.PaymentRequestStatus;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 付款申请 Mapper。 */
@Mapper
public interface PaymentRequestMapper extends BaseMapper<PaymentRequest> {

  String COLUMNS =
      "id, doc_no, company_id, supplier_id, invoice_id, amount, currency, status, requested_by,"
          + " approved_by, approved_at, paid_at, version, created_at, updated_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="invoiceId != null">AND invoice_id = #{invoiceId}</if>
        <if test="status != null">AND status = #{status}</if>
        <if test="keyword != null and keyword != ''">AND doc_no LIKE CONCAT('%', #{keyword}, '%')</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM payment_request " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<PaymentRequest> findPage(
      @Param("companyId") Long companyId,
      @Param("invoiceId") Long invoiceId,
      @Param("status") PaymentRequestStatus status,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM payment_request " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("invoiceId") Long invoiceId,
      @Param("status") PaymentRequestStatus status,
      @Param("keyword") String keyword);

  @Select("SELECT " + COLUMNS + " FROM payment_request WHERE id = #{id}")
  Optional<PaymentRequest> findById(@Param("id") long id);

  @Select(
      "SELECT COALESCE(SUM(amount), 0) FROM payment_request"
          + " WHERE invoice_id = #{invoiceId} AND status IN ('REQUESTED','APPROVED')")
  java.math.BigDecimal sumActiveByInvoice(@Param("invoiceId") long invoiceId);

  @Update(
      """
      UPDATE payment_request
      SET status = 'APPROVED', approved_by = #{approverId}, approved_at = CURRENT_TIMESTAMP(3),
          version = version + 1
      WHERE id = #{id} AND status = 'REQUESTED' AND requested_by <> #{approverId}
        AND version = #{version}
      """)
  int approve(
      @Param("id") long id, @Param("approverId") long approverId, @Param("version") int version);

  @Update(
      """
      UPDATE payment_request SET status = 'PAID', paid_at = CURRENT_TIMESTAMP(3), version = version + 1
      WHERE id = #{id} AND status = 'APPROVED' AND version = #{version}
      """)
  int markPaid(@Param("id") long id, @Param("version") int version);
}

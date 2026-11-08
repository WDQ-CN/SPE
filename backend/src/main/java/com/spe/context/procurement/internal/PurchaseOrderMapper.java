package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.procurement.api.PurchaseOrderStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 采购订单 Mapper。 */
@Mapper
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {

  String COLUMNS =
      "id, doc_no, company_id, supplier_id, plant_id, source_pr_id, currency, payment_terms,"
          + " amount, status, created_by, approved_by, approved_at, version, created_at, updated_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="supplierId != null">AND supplier_id = #{supplierId}</if>
        <if test="status != null">AND status = #{status}</if>
        <if test="keyword != null and keyword != ''">
          AND doc_no LIKE CONCAT('%', #{keyword}, '%')
        </if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM purchase_order " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<PurchaseOrder> findPage(
      @Param("companyId") Long companyId,
      @Param("supplierId") Long supplierId,
      @Param("status") PurchaseOrderStatus status,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM purchase_order " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("supplierId") Long supplierId,
      @Param("status") PurchaseOrderStatus status,
      @Param("keyword") String keyword);

  @Select("SELECT " + COLUMNS + " FROM purchase_order WHERE id = #{id}")
  Optional<PurchaseOrder> findById(@Param("id") long id);

  @Update(
      """
      UPDATE purchase_order
      SET source_rfq_id = #{rfqId}, source_award_id = #{awardId}, version = version + 1
      WHERE id = #{id}
      """)
  int linkSourcingSource(
      @Param("id") long id, @Param("rfqId") long rfqId, @Param("awardId") long awardId);

  @Update(
      "UPDATE purchase_order SET contract_id = #{contractId}, version = version + 1"
          + " WHERE id = #{id}")
  int linkContract(@Param("id") long id, @Param("contractId") long contractId);

  @Update(
      """
      UPDATE purchase_order
      SET source_auction_id = #{auctionId}, auction_result_id = #{resultId}, version = version + 1
      WHERE id = #{id}
      """)
  int linkAuctionResult(
      @Param("id") long id, @Param("auctionId") long auctionId, @Param("resultId") long resultId);

  /** 收货/开票/取消前锁定订单头：把“检查状态 + 写子单”串行化，避免与取消并发。 */
  @Select("SELECT " + COLUMNS + " FROM purchase_order WHERE id = #{id} FOR UPDATE")
  Optional<PurchaseOrder> findByIdForUpdate(@Param("id") long id);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM purchase_order WHERE id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + "</script>")
  List<PurchaseOrder> findByIds(@Param("ids") Collection<Long> ids);

  @Select(
      "SELECT COUNT(*) FROM purchase_order WHERE source_pr_id = #{requisitionId}"
          + " AND status <> 'CANCELED'")
  long countActiveBySourcePr(@Param("requisitionId") long requisitionId);

  @Update(
      """
      UPDATE purchase_order
      SET supplier_id = #{supplierId}, plant_id = #{plantId}, currency = #{currency},
          payment_terms = #{paymentTerms}, amount = #{amount}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT' AND version = #{version}
      """)
  int updateDraft(
      @Param("id") long id,
      @Param("supplierId") long supplierId,
      @Param("plantId") long plantId,
      @Param("currency") String currency,
      @Param("paymentTerms") String paymentTerms,
      @Param("amount") java.math.BigDecimal amount,
      @Param("version") int version);

  @Update(
      """
      UPDATE purchase_order
      SET status = 'APPROVED', approved_by = #{approverId}, approved_at = CURRENT_TIMESTAMP(3),
          version = version + 1
      WHERE id = #{id} AND status = 'DRAFT' AND created_by <> #{approverId}
        AND version = #{version}
      """)
  int approve(
      @Param("id") long id, @Param("approverId") long approverId, @Param("version") int version);

  @Update(
      """
      UPDATE purchase_order SET status = 'REJECTED', version = version + 1
      WHERE id = #{id} AND status = 'DRAFT' AND created_by <> #{approverId}
        AND version = #{version}
      """)
  int reject(
      @Param("id") long id, @Param("approverId") long approverId, @Param("version") int version);

  @Update(
      "UPDATE purchase_order SET status = 'SENT', version = version + 1"
          + " WHERE id = #{id} AND status = 'APPROVED' AND version = #{version}")
  int send(@Param("id") long id, @Param("version") int version);

  @Update(
      """
      UPDATE purchase_order SET status = 'CANCELED', version = version + 1
      WHERE id = #{id} AND status IN ('DRAFT','APPROVED','SENT') AND version = #{version}
      """)
  int cancel(@Param("id") long id, @Param("version") int version);

  /**
   * 收货后按行重算 PO 状态：全部收满 → RECEIVED，否则 PARTIAL_RECEIVED。
   *
   * <p>只更新 SENT/PARTIAL_RECEIVED，避免把已取消/已完成订单改回中间态。
   */
  @Update(
      """
      UPDATE purchase_order po
      SET po.status = CASE
            WHEN NOT EXISTS (
              SELECT 1 FROM purchase_order_line l
              WHERE l.po_id = po.id AND l.received_qty < l.qty
            ) THEN 'RECEIVED'
            ELSE 'PARTIAL_RECEIVED'
          END,
          po.version = po.version + 1
      WHERE po.id = #{id} AND po.status IN ('SENT','PARTIAL_RECEIVED')
      """)
  int refreshReceiptStatus(@Param("id") long id);

  /**
   * 冲销后重算 PO 状态：全无收货回到 SENT，部分收 PARTIAL_RECEIVED，全收满 RECEIVED。
   *
   * <p>不能复用 {@link #refreshReceiptStatus}：那条只从 SENT/PARTIAL 往 RECEIVED 走，
   * 冲销要把 RECEIVED 退回 PARTIAL/SENT。
   */
  @Update(
      """
      UPDATE purchase_order po
      SET po.status = CASE
            WHEN NOT EXISTS (
              SELECT 1 FROM purchase_order_line l
              WHERE l.po_id = po.id AND l.received_qty > 0
            ) THEN 'SENT'
            WHEN NOT EXISTS (
              SELECT 1 FROM purchase_order_line l
              WHERE l.po_id = po.id AND l.received_qty < l.qty
            ) THEN 'RECEIVED'
            ELSE 'PARTIAL_RECEIVED'
          END,
          po.version = po.version + 1
      WHERE po.id = #{id} AND po.status IN ('SENT','PARTIAL_RECEIVED','RECEIVED')
      """)
  int refreshReceiptStatusAfterReversal(@Param("id") long id);
}

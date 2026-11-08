package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 采购订单行 Mapper。 */
@Mapper
public interface PurchaseOrderLineMapper extends BaseMapper<PurchaseOrderLine> {

  String COLUMNS =
      "id, po_id, line_no, item_id, qty, uom, price, amount, received_qty, invoiced_qty,"
          + " returned_qty";

  @Select("SELECT " + COLUMNS + " FROM purchase_order_line WHERE po_id = #{poId} ORDER BY line_no")
  List<PurchaseOrderLine> findByPoId(@Param("poId") long poId);

  @Select("SELECT " + COLUMNS + " FROM purchase_order_line WHERE id = #{id} AND po_id = #{poId}")
  Optional<PurchaseOrderLine> findByIdAndPo(@Param("id") long id, @Param("poId") long poId);

  @Update(
      """
      UPDATE purchase_order_line
      SET award_line_id = #{awardLineId}, price_source = 'AWARD'
      WHERE id = #{id} AND po_id = #{poId}
      """)
  int linkAwardLine(
      @Param("id") long id, @Param("poId") long poId, @Param("awardLineId") long awardLineId);

  @Update(
      """
      UPDATE purchase_order_line
      SET contract_line_id = #{contractLineId}, price_source = 'CONTRACT'
      WHERE id = #{id} AND po_id = #{poId}
      """)
  int linkContractLine(
      @Param("id") long id,
      @Param("poId") long poId,
      @Param("contractLineId") long contractLineId);

  @Delete("DELETE FROM purchase_order_line WHERE po_id = #{poId}")
  int deleteByPoId(@Param("poId") long poId);

  /**
   * 收货累加：`received_qty + 本次 <= qty` 是最终防线（并发下两个收货请求都通过前置检查时，
   * 第二个 UPDATE 影响 0 行 → 2204）。行锁由 InnoDB 保证。
   */
  @Update(
      """
      UPDATE purchase_order_line
      SET received_qty = received_qty + #{receiveQty}
      WHERE id = #{id} AND po_id = #{poId} AND received_qty + #{receiveQty} <= qty
      """)
  int increaseReceived(
      @Param("id") long id, @Param("poId") long poId, @Param("receiveQty") BigDecimal receiveQty);

  /** 冲销回退已收数量；`received_qty >= qty` 是防负的最后一道守卫。 */
  @Update(
      "UPDATE purchase_order_line SET received_qty = received_qty - #{reverseQty}"
          + " WHERE id = #{id} AND po_id = #{poId} AND received_qty >= #{reverseQty}")
  int decreaseReceived(
      @Param("id") long id, @Param("poId") long poId, @Param("reverseQty") BigDecimal reverseQty);

  /** 退货累加：可退量 = 已收 - 已退；0 行表示超退（2232）。 */
  @Update(
      "UPDATE purchase_order_line SET returned_qty = returned_qty + #{returnQty}"
          + " WHERE id = #{id} AND po_id = #{poId}"
          + " AND received_qty - returned_qty >= #{returnQty}")
  int increaseReturned(
      @Param("id") long id, @Param("poId") long poId, @Param("returnQty") BigDecimal returnQty);

  /**
   * 异常匹配（EXCEPTION）发票批准时累加已开票数量。
   *
   * <p>异常只接受“与收货/价格的差异”，不能突破订单行数量这一硬上限；否则一张异常发票即可
   * 把 invoiced_qty 写到远超订单数量，后续付款失去业务基础。
   */
  @Update(
      "UPDATE purchase_order_line SET invoiced_qty = invoiced_qty + #{invoiceQty}"
          + " WHERE id = #{id} AND po_id = #{poId}"
          + " AND invoiced_qty + #{invoiceQty} <= qty")
  int increaseInvoiced(
      @Param("id") long id, @Param("poId") long poId, @Param("invoiceQty") BigDecimal invoiceQty);

  /**
   * 匹配通过（MATCHED）的发票批准时用的守卫累加：累计开票不得超过「已收 + 数量容差」。
   *
   * <p>两张发票在创建时都看到同一批可开票数量是可能的；真正防止重复占用的是批准时的行级守卫。
   * 异常匹配（EXCEPTION）已由审批人显式接受差异，走无上限的 {@link #increaseInvoiced}。
   */
  @Update(
      """
      UPDATE purchase_order_line
      SET invoiced_qty = invoiced_qty + #{invoiceQty}
      WHERE id = #{id} AND po_id = #{poId} AND invoiced_qty + #{invoiceQty} <= #{maxQty}
      """)
  int increaseInvoicedGuarded(
      @Param("id") long id,
      @Param("poId") long poId,
      @Param("invoiceQty") BigDecimal invoiceQty,
      @Param("maxQty") BigDecimal maxQty);
}

package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 采购退货 Mapper。 */
@Mapper
public interface PurchaseReturnMapper extends BaseMapper<PurchaseReturn> {

  String COLUMNS =
      "id, doc_no, company_id, supplier_id, po_id, warehouse_id, return_date, currency,"
          + " net_amount, invoiced, status, reason, created_by, version, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="poId != null">AND po_id = #{poId}</if>
        <if test="keyword != null and keyword != ''">AND doc_no LIKE CONCAT('%', #{keyword}, '%')</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM purchase_return " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<PurchaseReturn> findPage(
      @Param("companyId") Long companyId,
      @Param("poId") Long poId,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM purchase_return " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("poId") Long poId,
      @Param("keyword") String keyword);

  @Select("SELECT " + COLUMNS + " FROM purchase_return WHERE id = #{id}")
  Optional<PurchaseReturn> findById(@Param("id") long id);

  /** AP 子账：已开票退货的净额（未开票退货只影响 GR/IR，不影响 AP）。 */
  @Select(
      "SELECT COALESCE(SUM(net_amount), 0) FROM purchase_return"
          + " WHERE company_id = #{companyId} AND invoiced = 1 AND status = 'POSTED'"
          + " AND return_date <= #{asOf}")
  BigDecimal sumInvoicedAsOf(
      @Param("companyId") long companyId, @Param("asOf") LocalDate asOf);
}

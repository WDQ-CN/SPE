package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 收货单 Mapper。 */
@Mapper
public interface GoodsReceiptMapper extends BaseMapper<GoodsReceipt> {

  String COLUMNS =
      "id, doc_no, po_id, company_id, warehouse_id, status, created_by, received_at,"
          + " reversed_by, reversed_at, reverse_reason, version";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="poId != null">AND po_id = #{poId}</if>
        <if test="keyword != null and keyword != ''">
          AND doc_no LIKE CONCAT('%', #{keyword}, '%')
        </if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM goods_receipt " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<GoodsReceipt> findPage(
      @Param("companyId") Long companyId,
      @Param("poId") Long poId,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM goods_receipt " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("poId") Long poId,
      @Param("keyword") String keyword);

  @Select("SELECT " + COLUMNS + " FROM goods_receipt WHERE id = #{id}")
  Optional<GoodsReceipt> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM goods_receipt WHERE id = #{id} FOR UPDATE")
  Optional<GoodsReceipt> findByIdForUpdate(@Param("id") long id);

  @Update(
      """
      UPDATE goods_receipt
      SET status = 'REVERSED', reversed_by = #{operatorId}, reversed_at = CURRENT_TIMESTAMP(3),
          reverse_reason = #{reason}, version = version + 1
      WHERE id = #{id} AND status = 'POSTED' AND version = #{version}
      """)
  int reverse(
      @Param("id") long id,
      @Param("operatorId") long operatorId,
      @Param("reason") String reason,
      @Param("version") int version);

  /**
   * SoD：该用户是否给这张 PO 收过货（收货人不能审批/支付该 PO 的发票）。
   * 已冲销的收货也算：否则“收货后自行冲销”可把 SoD 计数清零。
   */
  @Select(
      "SELECT COUNT(*) FROM goods_receipt WHERE po_id = #{poId} AND created_by = #{userId}"
          + " AND status IN ('POSTED','REVERSED')")
  long countPostedByPoAndCreator(@Param("poId") long poId, @Param("userId") long userId);
}

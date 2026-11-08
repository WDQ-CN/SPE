package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.procurement.api.RequisitionStatus;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 请购单 Mapper：所有状态迁移都是带前置状态的守卫 UPDATE。 */
@Mapper
public interface PurchaseRequisitionMapper extends BaseMapper<PurchaseRequisition> {

  String COLUMNS =
      "id, doc_no, company_id, requester_id, need_date, currency, amount, status, version,"
          + " created_at, updated_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
        <if test="keyword != null and keyword != ''">
          AND doc_no LIKE CONCAT('%', #{keyword}, '%')
        </if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM purchase_requisition " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<PurchaseRequisition> findPage(
      @Param("companyId") Long companyId,
      @Param("status") RequisitionStatus status,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM purchase_requisition " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("status") RequisitionStatus status,
      @Param("keyword") String keyword);

  @Select("SELECT " + COLUMNS + " FROM purchase_requisition WHERE id = #{id}")
  Optional<PurchaseRequisition> findById(@Param("id") long id);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM purchase_requisition WHERE id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + "</script>")
  List<PurchaseRequisition> findByIds(@Param("ids") java.util.Collection<Long> ids);

  @Update(
      """
      UPDATE purchase_requisition
      SET need_date = #{needDate}, currency = #{currency}, amount = #{amount}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT' AND version = #{version}
      """)
  int updateDraft(
      @Param("id") long id,
      @Param("needDate") java.time.LocalDate needDate,
      @Param("currency") String currency,
      @Param("amount") java.math.BigDecimal amount,
      @Param("version") int version);

  @Update(
      """
      UPDATE purchase_requisition SET status = 'SUBMITTED', version = version + 1
      WHERE id = #{id} AND status = 'DRAFT' AND requester_id = #{requesterId} AND version = #{version}
      """)
  int submit(
      @Param("id") long id, @Param("requesterId") long requesterId, @Param("version") int version);

  @Update(
      """
      UPDATE purchase_requisition SET status = 'APPROVED', version = version + 1
      WHERE id = #{id} AND status = 'SUBMITTED' AND requester_id <> #{approverId}
        AND version = #{version}
      """)
  int approve(
      @Param("id") long id, @Param("approverId") long approverId, @Param("version") int version);

  @Update(
      """
      UPDATE purchase_requisition SET status = 'REJECTED', version = version + 1
      WHERE id = #{id} AND status = 'SUBMITTED' AND requester_id <> #{approverId}
        AND version = #{version}
      """)
  int reject(
      @Param("id") long id, @Param("approverId") long approverId, @Param("version") int version);

  @Update(
      """
      UPDATE purchase_requisition SET status = 'CONVERTED', version = version + 1
      WHERE id = #{id} AND status = 'APPROVED' AND version = #{version}
      """)
  int convert(@Param("id") long id, @Param("version") int version);
}

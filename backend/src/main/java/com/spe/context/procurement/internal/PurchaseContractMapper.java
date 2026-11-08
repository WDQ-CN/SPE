package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 合同 Mapper。 */
@Mapper
public interface PurchaseContractMapper extends BaseMapper<PurchaseContract> {

  String COLUMNS =
      "id, contract_no, company_id, supplier_id, title, currency, valid_from, valid_to,"
          + " payment_terms, status, total_limit_amount, total_limit_qty, source_award_id,"
          + " version, created_by, created_at, approved_by, approved_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="supplierId != null">AND supplier_id = #{supplierId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM purchase_contract " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<PurchaseContract> findPage(
      @Param("companyId") Long companyId,
      @Param("supplierId") Long supplierId,
      @Param("status") PurchaseContractStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM purchase_contract " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("supplierId") Long supplierId,
      @Param("status") PurchaseContractStatus status);

  @Select("SELECT " + COLUMNS + " FROM purchase_contract WHERE id = #{id}")
  Optional<PurchaseContract> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM purchase_contract WHERE id = #{id} FOR UPDATE")
  Optional<PurchaseContract> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE purchase_contract
      SET status = 'ACTIVE', approved_by = #{operator}, approved_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markActive(
      @Param("id") long id, @Param("operator") long operator, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE purchase_contract SET status = 'TERMINATED', version = version + 1
      WHERE id = #{id} AND status = 'ACTIVE'
      """)
  int markTerminated(@Param("id") long id);
}

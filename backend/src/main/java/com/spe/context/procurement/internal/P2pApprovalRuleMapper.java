package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.procurement.api.ApprovalDocType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 审批矩阵 Mapper。 */
@Mapper
public interface P2pApprovalRuleMapper extends BaseMapper<P2pApprovalRule> {

  String COLUMNS =
      "id, company_id, doc_type, min_amount, max_amount, required_role, priority, status";

  /**
   * 命中规则：公司级优先于全局，其次按 priority 升序。
   *
   * <p>区间两端都含；`max_amount IS NULL` 表示无上限。
   */
  @Select(
      """
      SELECT id, company_id, doc_type, min_amount, max_amount, required_role, priority, status
      FROM p2p_approval_rule
      WHERE doc_type = #{docType} AND status = 'ACTIVE'
        AND (company_id = #{companyId} OR company_id IS NULL)
        AND min_amount <= #{amount}
        AND (max_amount IS NULL OR #{amount} <= max_amount)
      ORDER BY CASE WHEN company_id IS NULL THEN 1 ELSE 0 END, priority, id
      LIMIT 1
      """)
  Optional<P2pApprovalRule> findApplicable(
      @Param("docType") ApprovalDocType docType,
      @Param("companyId") long companyId,
      @Param("amount") BigDecimal amount);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM p2p_approval_rule"
          + "<where><if test=\"docType != null\">doc_type = #{docType}</if></where>"
          + " ORDER BY doc_type, CASE WHEN company_id IS NULL THEN 1 ELSE 0 END, priority, id</script>")
  List<P2pApprovalRule> findAll(@Param("docType") ApprovalDocType docType);
}

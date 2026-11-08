package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 客户收款 Mapper。 */
@Mapper
public interface CustomerReceiptMapper extends BaseMapper<CustomerReceipt> {

  String COLUMNS =
      "id, receipt_no, company_id, customer_id, currency, amount, method, status, received_at,"
          + " version, created_by, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="customerId != null">AND customer_id = #{customerId}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM customer_receipt " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<CustomerReceipt> findPage(
      @Param("companyId") Long companyId,
      @Param("customerId") Long customerId,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM customer_receipt " + SEARCH_CONDITION + "</script>")
  long countBy(@Param("companyId") Long companyId, @Param("customerId") Long customerId);

  @Select("SELECT " + COLUMNS + " FROM customer_receipt WHERE id = #{id}")
  Optional<CustomerReceipt> findById(@Param("id") long id);
}

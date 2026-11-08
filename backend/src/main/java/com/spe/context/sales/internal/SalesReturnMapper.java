package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 销售退货 Mapper。 */
@Mapper
public interface SalesReturnMapper extends BaseMapper<SalesReturn> {

  String COLUMNS =
      "id, return_no, company_id, customer_id, shipment_id, order_id, currency, status,"
          + " net_amount, tax_amount, total_amount, reason, credited_at, version, created_by,"
          + " created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="customerId != null">AND customer_id = #{customerId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM sales_return " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<SalesReturn> findPage(
      @Param("companyId") Long companyId,
      @Param("customerId") Long customerId,
      @Param("status") SalesReturnStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM sales_return " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("customerId") Long customerId,
      @Param("status") SalesReturnStatus status);

  @Select("SELECT " + COLUMNS + " FROM sales_return WHERE id = #{id}")
  Optional<SalesReturn> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM sales_return WHERE id = #{id} FOR UPDATE")
  Optional<SalesReturn> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE sales_return
      SET status = 'CREDITED', credited_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'REQUESTED'
      """)
  int markCredited(@Param("id") long id, @Param("now") LocalDateTime now);
}

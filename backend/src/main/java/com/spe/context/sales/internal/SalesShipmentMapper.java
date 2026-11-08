package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 销售发货 Mapper。 */
@Mapper
public interface SalesShipmentMapper extends BaseMapper<SalesShipment> {

  String COLUMNS =
      "id, shipment_no, company_id, customer_id, order_id, warehouse_id, currency, status,"
          + " shipped_at, created_by, version, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="orderId != null">AND order_id = #{orderId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM sales_shipment " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<SalesShipment> findPage(
      @Param("companyId") Long companyId,
      @Param("orderId") Long orderId,
      @Param("status") SalesShipmentStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM sales_shipment " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("orderId") Long orderId,
      @Param("status") SalesShipmentStatus status);

  @Select("SELECT " + COLUMNS + " FROM sales_shipment WHERE id = #{id}")
  Optional<SalesShipment> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM sales_shipment WHERE id = #{id} FOR UPDATE")
  Optional<SalesShipment> lockById(@Param("id") long id);
}

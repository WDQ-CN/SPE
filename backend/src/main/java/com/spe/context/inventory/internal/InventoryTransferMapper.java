package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 调拨单 Mapper。 */
@Mapper
public interface InventoryTransferMapper extends BaseMapper<InventoryTransfer> {

  String COLUMNS =
      "id, transfer_no, company_id, from_plant_id, from_warehouse_id, from_location_id,"
          + " to_plant_id, to_warehouse_id, to_location_id, status, reason, requested_by,"
          + " approved_by, approved_at, shipped_at, received_at, version, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_transfer " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<InventoryTransfer> findPage(
      @Param("companyId") Long companyId,
      @Param("status") InventoryTransferStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM inventory_transfer " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId, @Param("status") InventoryTransferStatus status);

  @Select("SELECT " + COLUMNS + " FROM inventory_transfer WHERE id = #{id}")
  Optional<InventoryTransfer> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM inventory_transfer WHERE id = #{id} FOR UPDATE")
  Optional<InventoryTransfer> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE inventory_transfer
      SET status = 'IN_TRANSIT', approved_by = #{operator}, approved_at = #{now},
          shipped_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markShipped(
      @Param("id") long id, @Param("operator") long operator, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE inventory_transfer
      SET status = 'RECEIVED', received_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'IN_TRANSIT'
      """)
  int markReceived(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE inventory_transfer SET status = 'CANCELED', version = version + 1
      WHERE id = #{id} AND status IN ('DRAFT','IN_TRANSIT')
      """)
  int markCanceled(@Param("id") long id);
}

package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 库存预留 Mapper。 */
@Mapper
public interface InventoryReservationMapper extends BaseMapper<InventoryReservation> {

  String COLUMNS =
      "id, company_id, warehouse_id, location_id, item_id, lot_id, serial_id, qty, source_type,"
          + " source_id, source_line_id, status, expires_at, created_by, version, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="warehouseId != null">AND warehouse_id = #{warehouseId}</if>
        <if test="itemId != null">AND item_id = #{itemId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_reservation " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<InventoryReservation> findPage(
      @Param("warehouseId") Long warehouseId,
      @Param("itemId") Long itemId,
      @Param("status") InventoryReservationStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM inventory_reservation " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("warehouseId") Long warehouseId,
      @Param("itemId") Long itemId,
      @Param("status") InventoryReservationStatus status);

  @Select("SELECT " + COLUMNS + " FROM inventory_reservation WHERE id = #{id}")
  Optional<InventoryReservation> findById(@Param("id") long id);

  @Update(
      """
      UPDATE inventory_reservation SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = 'ACTIVE'
      """)
  int close(@Param("id") long id, @Param("target") InventoryReservationStatus target);

  @Select(
      "SELECT " + COLUMNS + " FROM inventory_reservation"
          + " WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < #{now}")
  List<InventoryReservation> findExpired(@Param("now") java.time.LocalDateTime now);
}

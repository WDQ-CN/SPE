package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 库存移动 Mapper（只追加，无更新/删除端点）。 */
@Mapper
public interface InventoryMovementMapper extends BaseMapper<InventoryMovement> {

  String COLUMNS =
      "id, movement_no, company_id, plant_id, warehouse_id, item_id, lot_id, location_id,"
          + " movement_type, direction,"
          + " qty, uom, unit_cost, amount, currency, source_type, source_id, source_line_id,"
          + " occurred_at, created_by, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="warehouseId != null">AND warehouse_id = #{warehouseId}</if>
        <if test="itemId != null">AND item_id = #{itemId}</if>
        <if test="lotId != null">AND lot_id = #{lotId}</if>
        <if test="locationId != null">AND location_id = #{locationId}</if>
        <if test="sourceType != null and sourceType != ''">AND source_type = #{sourceType}</if>
        <if test="sourceId != null">AND source_id = #{sourceId}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_movement " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<InventoryMovement> findPage(
      @Param("companyId") Long companyId,
      @Param("warehouseId") Long warehouseId,
      @Param("itemId") Long itemId,
      @Param("lotId") Long lotId,
      @Param("locationId") Long locationId,
      @Param("sourceType") String sourceType,
      @Param("sourceId") Long sourceId,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM inventory_movement " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("warehouseId") Long warehouseId,
      @Param("itemId") Long itemId,
      @Param("lotId") Long lotId,
      @Param("locationId") Long locationId,
      @Param("sourceType") String sourceType,
      @Param("sourceId") Long sourceId);

  @Select(
      "SELECT " + COLUMNS + " FROM inventory_movement"
          + " WHERE source_type = #{sourceType} AND source_id = #{sourceId}"
          + " AND source_line_id = #{sourceLineId} AND movement_type = #{movementType}")
  Optional<InventoryMovement> findBySource(
      @Param("sourceType") String sourceType,
      @Param("sourceId") long sourceId,
      @Param("sourceLineId") long sourceLineId,
      @Param("movementType") InventoryMovementType movementType);
}

package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 库存冻结 Mapper。 */
@Mapper
public interface InventoryFreezeMapper extends BaseMapper<InventoryFreeze> {

  String COLUMNS =
      "id, company_id, warehouse_id, location_id, item_id, lot_id, serial_id, qty, reason,"
          + " source_type, source_id, status, created_by, version, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="warehouseId != null">AND warehouse_id = #{warehouseId}</if>
        <if test="itemId != null">AND item_id = #{itemId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_freeze " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<InventoryFreeze> findPage(
      @Param("warehouseId") Long warehouseId,
      @Param("itemId") Long itemId,
      @Param("status") InventoryFreezeStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM inventory_freeze " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("warehouseId") Long warehouseId,
      @Param("itemId") Long itemId,
      @Param("status") InventoryFreezeStatus status);

  @Select("SELECT " + COLUMNS + " FROM inventory_freeze WHERE id = #{id}")
  Optional<InventoryFreeze> findById(@Param("id") long id);

  @Update(
      """
      UPDATE inventory_freeze SET status = 'RELEASED', version = version + 1
      WHERE id = #{id} AND status = 'ACTIVE'
      """)
  int release(@Param("id") long id);
}

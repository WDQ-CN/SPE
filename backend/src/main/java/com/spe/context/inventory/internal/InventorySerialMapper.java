package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 序列号 Mapper。 */
@Mapper
public interface InventorySerialMapper extends BaseMapper<InventorySerial> {

  String COLUMNS =
      "id, serial_no, item_id, lot_id, supplier_id, source_type, source_id, source_line_id,"
          + " warehouse_id, location_id, status, version, created_at, updated_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="itemId != null">AND item_id = #{itemId}</if>
        <if test="lotId != null">AND lot_id = #{lotId}</if>
        <if test="status != null">AND status = #{status}</if>
        <if test="keyword != null and keyword != ''">AND serial_no LIKE CONCAT('%', #{keyword}, '%')</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_serial " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<InventorySerial> findPage(
      @Param("itemId") Long itemId,
      @Param("lotId") Long lotId,
      @Param("status") InventorySerialStatus status,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM inventory_serial " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("itemId") Long itemId,
      @Param("lotId") Long lotId,
      @Param("status") InventorySerialStatus status,
      @Param("keyword") String keyword);

  @Select(
      "SELECT " + COLUMNS + " FROM inventory_serial WHERE item_id = #{itemId} AND serial_no = #{serialNo}")
  Optional<InventorySerial> findByItemAndSerial(
      @Param("itemId") long itemId, @Param("serialNo") String serialNo);

  @Select("SELECT " + COLUMNS + " FROM inventory_serial WHERE id = #{id}")
  Optional<InventorySerial> findById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM inventory_serial"
          + " WHERE source_type = #{sourceType} AND source_id = #{sourceId}"
          + " AND source_line_id = #{sourceLineId} AND status = 'IN_STOCK'")
  List<InventorySerial> findInStockBySource(
      @Param("sourceType") String sourceType,
      @Param("sourceId") long sourceId,
      @Param("sourceLineId") long sourceLineId);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_serial WHERE id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + "</script>")
  List<InventorySerial> findByIds(@Param("ids") Collection<Long> ids);

  @Update(
      """
      UPDATE inventory_serial SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") InventorySerialStatus target,
      @Param("expected") InventorySerialStatus expected);

  @Update(
      """
      UPDATE inventory_serial
      SET warehouse_id = #{warehouseId}, location_id = #{locationId}, version = version + 1
      WHERE id = #{id} AND status = 'IN_STOCK'
      """)
  int updateLocation(
      @Param("id") long id,
      @Param("warehouseId") long warehouseId,
      @Param("locationId") long locationId);
}

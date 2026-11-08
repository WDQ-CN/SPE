package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 库存批次 Mapper。 */
@Mapper
public interface InventoryLotMapper extends BaseMapper<InventoryLot> {

  String COLUMNS =
      "id, lot_no, item_id, supplier_id, source_type, source_id, production_date, expiry_date,"
          + " status, version, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="itemId != null">AND item_id = #{itemId}</if>
        <if test="keyword != null and keyword != ''">AND lot_no LIKE CONCAT('%', #{keyword}, '%')</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_lot " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<InventoryLot> findPage(
      @Param("itemId") Long itemId,
      @Param("keyword") String keyword,
      @Param("status") InventoryLotStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM inventory_lot " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("itemId") Long itemId,
      @Param("keyword") String keyword,
      @Param("status") InventoryLotStatus status);

  @Select(
      "SELECT " + COLUMNS + " FROM inventory_lot WHERE item_id = #{itemId} AND lot_no = #{lotNo}")
  Optional<InventoryLot> findByItemAndLotNo(
      @Param("itemId") long itemId, @Param("lotNo") String lotNo);

  @Select("SELECT " + COLUMNS + " FROM inventory_lot WHERE id = #{id}")
  Optional<InventoryLot> findById(@Param("id") long id);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_lot WHERE id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + "</script>")
  List<InventoryLot> findByIds(@Param("ids") Collection<Long> ids);

  @Select(
      """
      <script>
      SELECT l.id AS lotId, l.lot_no AS lotNo, l.item_id AS itemId, l.expiry_date AS expiryDate,
             SUM(s.qty_on_hand) AS qtyOnHand
      FROM inventory_lot l
      JOIN inventory_stock s ON s.lot_id = l.id AND s.qty_on_hand > 0
      WHERE l.status = 'ACTIVE' AND l.expiry_date IS NOT NULL
        AND l.expiry_date &lt;= #{cutoff}
        <if test="warehouseId != null">AND s.warehouse_id = #{warehouseId}</if>
      GROUP BY l.id, l.lot_no, l.item_id, l.expiry_date
      ORDER BY l.expiry_date, l.id
      </script>
      """)
  List<ExpiringLotRow> findExpiring(
      @Param("cutoff") LocalDate cutoff, @Param("warehouseId") Long warehouseId);

  @Update(
      """
      UPDATE inventory_lot SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") InventoryLotStatus target,
      @Param("expected") InventoryLotStatus expected,
      @Param("version") int version);

  record ExpiringLotRow(
      long lotId, String lotNo, long itemId, java.time.LocalDate expiryDate, BigDecimal qtyOnHand) {}
}

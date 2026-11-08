package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 库存余额 Mapper。收货用 UPSERT，冲销用带非负守卫的 UPDATE。 */
@Mapper
public interface InventoryStockMapper extends BaseMapper<InventoryStock> {

  String COLUMNS =
      "id, company_id, plant_id, warehouse_id, item_id, lot_id, location_id, qty_on_hand,"
          + " qty_reserved, qty_frozen, total_value, avg_unit_cost, version";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="plantId != null">AND plant_id = #{plantId}</if>
        <if test="warehouseId != null">AND warehouse_id = #{warehouseId}</if>
        <if test="itemId != null">AND item_id = #{itemId}</if>
        <if test="lotId != null">AND lot_id = #{lotId}</if>
        <if test="locationId != null">AND location_id = #{locationId}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_stock " + SEARCH_CONDITION
          + " ORDER BY warehouse_id, item_id LIMIT #{size} OFFSET #{offset}</script>")
  List<InventoryStock> findPage(
      @Param("companyId") Long companyId,
      @Param("plantId") Long plantId,
      @Param("warehouseId") Long warehouseId,
      @Param("itemId") Long itemId,
      @Param("lotId") Long lotId,
      @Param("locationId") Long locationId,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM inventory_stock " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("plantId") Long plantId,
      @Param("warehouseId") Long warehouseId,
      @Param("itemId") Long itemId,
      @Param("lotId") Long lotId,
      @Param("locationId") Long locationId);

  /**
   * 收货 UPSERT：移动平均。avg 必须在 qty/total_value 赋值**之前**计算，否则 MySQL 左到右求值会
   * 把新数量再算一遍。
   */
  @Update(
      """
      INSERT INTO inventory_stock
        (company_id, plant_id, warehouse_id, item_id, lot_id, location_id,
         qty_on_hand, total_value, avg_unit_cost, version)
      VALUES
        (#{companyId}, #{plantId}, #{warehouseId}, #{itemId}, #{lotId}, #{locationId},
         #{qty}, #{amount}, CASE WHEN #{qty} = 0 THEN 0 ELSE #{amount} / #{qty} END, 0)
      ON DUPLICATE KEY UPDATE
        avg_unit_cost = CASE
          WHEN qty_on_hand + VALUES(qty_on_hand) = 0 THEN 0
          ELSE (total_value + VALUES(total_value)) / (qty_on_hand + VALUES(qty_on_hand))
        END,
        total_value = total_value + VALUES(total_value),
        qty_on_hand = qty_on_hand + VALUES(qty_on_hand),
        version = version + 1
      """)
  int upsertReceipt(
      @Param("companyId") long companyId,
      @Param("plantId") long plantId,
      @Param("warehouseId") long warehouseId,
      @Param("itemId") long itemId,
      @Param("lotId") long lotId,
      @Param("locationId") long locationId,
      @Param("qty") BigDecimal qty,
      @Param("amount") BigDecimal amount);

  /** FEFO：按批次效期升序（无有效期最后）返回有库存的维度。 */
  @Select(
      """
      <script>
      SELECT s.lot_id AS lotId, l.lot_no AS lotNo, l.expiry_date AS expiryDate,
             s.location_id AS locationId,
             s.qty_on_hand - s.qty_reserved - s.qty_frozen AS availableQty
      FROM inventory_stock s
      JOIN inventory_lot l ON l.id = s.lot_id
      WHERE s.company_id = #{companyId} AND s.warehouse_id = #{warehouseId}
        AND s.item_id = #{itemId}
        AND s.qty_on_hand - s.qty_reserved - s.qty_frozen > 0
        AND l.status = 'ACTIVE'
        AND (l.expiry_date IS NULL OR l.expiry_date &gt;= CURRENT_DATE)
        <if test="locationId != null">AND s.location_id = #{locationId}</if>
      ORDER BY CASE WHEN l.expiry_date IS NULL THEN 1 ELSE 0 END,
               l.expiry_date, l.id, s.location_id
      </script>
      """)
  List<FefoStockRow> findFefo(
      @Param("companyId") long companyId,
      @Param("warehouseId") long warehouseId,
      @Param("itemId") long itemId,
      @Param("locationId") Long locationId);

  /** 预留：可用量守卫；0 行表示可用量不足或维度不存在。 */
  @Update(
      """
      UPDATE inventory_stock
      SET qty_reserved = qty_reserved + #{qty}, version = version + 1
      WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId}
        AND location_id = #{locationId} AND item_id = #{itemId} AND lot_id = #{lotId}
        AND qty_on_hand - qty_reserved - qty_frozen >= #{qty}
      """)
  int reserve(
      @Param("companyId") long companyId,
      @Param("warehouseId") long warehouseId,
      @Param("locationId") long locationId,
      @Param("itemId") long itemId,
      @Param("lotId") long lotId,
      @Param("qty") BigDecimal qty);

  @Update(
      """
      UPDATE inventory_stock
      SET qty_reserved = qty_reserved - #{qty}, version = version + 1
      WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId}
        AND location_id = #{locationId} AND item_id = #{itemId} AND lot_id = #{lotId}
        AND qty_reserved >= #{qty}
      """)
  int releaseReserved(
      @Param("companyId") long companyId,
      @Param("warehouseId") long warehouseId,
      @Param("locationId") long locationId,
      @Param("itemId") long itemId,
      @Param("lotId") long lotId,
      @Param("qty") BigDecimal qty);

  /** 冻结：可用量守卫；0 行表示可用量不足或维度不存在。 */
  @Update(
      """
      UPDATE inventory_stock
      SET qty_frozen = qty_frozen + #{qty}, version = version + 1
      WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId}
        AND location_id = #{locationId} AND item_id = #{itemId} AND lot_id = #{lotId}
        AND qty_on_hand - qty_reserved - qty_frozen >= #{qty}
      """)
  int freeze(
      @Param("companyId") long companyId,
      @Param("warehouseId") long warehouseId,
      @Param("locationId") long locationId,
      @Param("itemId") long itemId,
      @Param("lotId") long lotId,
      @Param("qty") BigDecimal qty);

  @Update(
      """
      UPDATE inventory_stock
      SET qty_frozen = qty_frozen - #{qty}, version = version + 1
      WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId}
        AND location_id = #{locationId} AND item_id = #{itemId} AND lot_id = #{lotId}
        AND qty_frozen >= #{qty}
      """)
  int releaseFrozen(
      @Param("companyId") long companyId,
      @Param("warehouseId") long warehouseId,
      @Param("locationId") long locationId,
      @Param("itemId") long itemId,
      @Param("lotId") long lotId,
      @Param("qty") BigDecimal qty);

  @Select(
      """
      SELECT COALESCE(SUM(qty_on_hand), 0) FROM inventory_stock
      WHERE location_id = #{locationId} AND item_id = #{itemId}
      """)
  BigDecimal sumAtLocationByItem(
      @Param("locationId") long locationId, @Param("itemId") long itemId);

  @Select(
      """
      SELECT COALESCE(SUM(qty_on_hand), 0) FROM inventory_stock
      WHERE location_id = #{locationId}
      """)
  BigDecimal sumAtLocation(@Param("locationId") long locationId);

  @Select(
      """
      SELECT id, company_id, plant_id, warehouse_id, item_id, lot_id, location_id, qty_on_hand,
             qty_reserved, qty_frozen, total_value, avg_unit_cost, version
      FROM inventory_stock
      WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId}
        AND item_id = #{itemId} AND lot_id = #{lotId} AND location_id = #{locationId}
      """)
  java.util.Optional<InventoryStock> findOne(
      @Param("companyId") long companyId,
      @Param("warehouseId") long warehouseId,
      @Param("itemId") long itemId,
      @Param("lotId") long lotId,
      @Param("locationId") long locationId);

  /** 盘点过账用：锁住维度行，检查快照后数量未被并发改动。 */
  @Select(
      """
      SELECT id, company_id, plant_id, warehouse_id, item_id, lot_id, location_id, qty_on_hand,
             qty_reserved, qty_frozen, total_value, avg_unit_cost, version
      FROM inventory_stock
      WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId}
        AND item_id = #{itemId} AND lot_id = #{lotId} AND location_id = #{locationId}
      FOR UPDATE
      """)
  java.util.Optional<InventoryStock> lockOne(
      @Param("companyId") long companyId,
      @Param("warehouseId") long warehouseId,
      @Param("itemId") long itemId,
      @Param("lotId") long lotId,
      @Param("locationId") long locationId);

  @Select(
      "SELECT " + COLUMNS + " FROM inventory_stock WHERE company_id = #{companyId}"
          + " ORDER BY warehouse_id, item_id, lot_id, location_id")
  List<InventoryStock> findByCompany(@Param("companyId") long companyId);

  @Select(
      """
      <script>
      SELECT id, company_id, plant_id, warehouse_id, item_id, lot_id, location_id, qty_on_hand,
             qty_reserved, qty_frozen, total_value, avg_unit_cost, version
      FROM inventory_stock
      WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId} AND qty_on_hand > 0
        <if test="itemId != null">AND item_id = #{itemId}</if>
        <if test="lotId != null">AND lot_id = #{lotId}</if>
        <if test="locationId != null">AND location_id = #{locationId}</if>
      ORDER BY item_id, lot_id, location_id
      </script>
      """)
  List<InventoryStock> findForSnapshot(
      @Param("companyId") long companyId,
      @Param("warehouseId") long warehouseId,
      @Param("itemId") Long itemId,
      @Param("lotId") Long lotId,
      @Param("locationId") Long locationId);

  /** 冲销/退货：非负守卫；0 行表示库存不足或记录不存在。 */
  @Update(
      """
      UPDATE inventory_stock
      SET avg_unit_cost = CASE
            WHEN qty_on_hand - #{qty} = 0 THEN 0
            ELSE (total_value - #{amount}) / (qty_on_hand - #{qty})
          END,
          total_value = total_value - #{amount},
          qty_on_hand = qty_on_hand - #{qty},
          version = version + 1
      WHERE company_id = #{companyId} AND plant_id = #{plantId}
        AND warehouse_id = #{warehouseId} AND item_id = #{itemId}
        AND lot_id = #{lotId} AND location_id = #{locationId}
        AND qty_on_hand >= #{qty} AND total_value >= #{amount}
        AND qty_on_hand - qty_reserved - qty_frozen >= #{qty}
      """)
  int decreaseForReversal(
      @Param("companyId") long companyId,
      @Param("plantId") long plantId,
      @Param("warehouseId") long warehouseId,
      @Param("itemId") long itemId,
      @Param("lotId") long lotId,
      @Param("locationId") long locationId,
      @Param("qty") BigDecimal qty,
      @Param("amount") BigDecimal amount);

  record FefoStockRow(
      long lotId,
      String lotNo,
      java.time.LocalDate expiryDate,
      long locationId,
      BigDecimal availableQty) {}
}

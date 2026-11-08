package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 估值层 Mapper。 */
@Mapper
public interface InventoryValuationLayerMapper extends BaseMapper<InventoryValuationLayer> {

  String COLUMNS =
      "id, company_id, warehouse_id, item_id, lot_id, method, source_movement_id, layer_date,"
          + " qty, remaining_qty, unit_cost, amount, remaining_amount, status, created_at";

  @Select(
      "SELECT " + COLUMNS + " FROM inventory_valuation_layer WHERE source_movement_id = #{movementId}")
  Optional<InventoryValuationLayer> findBySourceMovement(@Param("movementId") long movementId);

  @Select(
      """
      <script>
      SELECT id, company_id, warehouse_id, item_id, lot_id, method, source_movement_id, layer_date,
             qty, remaining_qty, unit_cost, amount, remaining_amount, status, created_at
      FROM inventory_valuation_layer
      WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId}
        AND item_id = #{itemId} AND status = 'OPEN' AND remaining_qty > 0
        <if test="lotId != null and lotId > 0">AND lot_id = #{lotId}</if>
      ORDER BY layer_date, id FOR UPDATE
      </script>
      """)
  List<InventoryValuationLayer> lockOpenLayers(
      @Param("companyId") long companyId,
      @Param("warehouseId") long warehouseId,
      @Param("itemId") long itemId,
      @Param("lotId") Long lotId);

  @Update(
      """
      UPDATE inventory_valuation_layer
      SET remaining_qty = remaining_qty - #{qty},
          remaining_amount = remaining_amount - #{amount},
          status = CASE WHEN remaining_qty - #{qty} &lt;= 0 THEN 'DEPLETED' ELSE 'OPEN' END
      WHERE id = #{id} AND remaining_qty &gt;= #{qty}
      """)
  int consume(
      @Param("id") long id,
      @Param("qty") BigDecimal qty,
      @Param("amount") BigDecimal amount);

  @Select(
      """
      SELECT warehouse_id AS warehouseId, item_id AS itemId, lot_id AS lotId,
             COALESCE(SUM(remaining_qty), 0) AS remainingQty,
             COALESCE(SUM(remaining_amount), 0) AS remainingAmount
      FROM inventory_valuation_layer
      WHERE company_id = #{companyId}
      GROUP BY warehouse_id, item_id, lot_id
      """)
  List<LayerTotal> totalsByItemLot(@Param("companyId") long companyId);

  record LayerTotal(
      long warehouseId, long itemId, long lotId, BigDecimal remainingQty,
      BigDecimal remainingAmount) {}
}

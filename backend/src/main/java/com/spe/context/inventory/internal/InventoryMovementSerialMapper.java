package com.spe.context.inventory.internal;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 库存移动-序列号关联 Mapper。 */
@Mapper
public interface InventoryMovementSerialMapper {

  @Insert(
      "INSERT INTO inventory_movement_serial (movement_id, serial_id, direction)"
          + " VALUES (#{movementId}, #{serialId}, #{direction})")
  int link(
      @Param("movementId") long movementId,
      @Param("serialId") long serialId,
      @Param("direction") String direction);

  @Select(
      """
      SELECT ms.movement_id AS movementId, m.movement_no AS movementNo,
             m.movement_type AS movementType, ms.direction AS direction,
             m.source_type AS sourceType, m.source_id AS sourceId, m.occurred_at AS occurredAt
      FROM inventory_movement_serial ms
      JOIN inventory_movement m ON m.id = ms.movement_id
      WHERE ms.serial_id = #{serialId}
      ORDER BY m.id
      """)
  List<SerialMovementRow> findMovements(@Param("serialId") long serialId);
}

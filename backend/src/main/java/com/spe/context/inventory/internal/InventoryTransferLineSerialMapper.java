package com.spe.context.inventory.internal;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 调拨行序列 Mapper。 */
@Mapper
public interface InventoryTransferLineSerialMapper {

  @Insert(
      "INSERT INTO inventory_transfer_line_serial (transfer_line_id, serial_id)"
          + " VALUES (#{transferLineId}, #{serialId})")
  int insert(
      @Param("transferLineId") long transferLineId, @Param("serialId") long serialId);

  @Select(
      "SELECT serial_id FROM inventory_transfer_line_serial WHERE transfer_line_id = #{lineId}")
  List<Long> findSerialIds(@Param("lineId") long lineId);
}

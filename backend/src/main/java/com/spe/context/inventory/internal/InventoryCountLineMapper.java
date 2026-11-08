package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 盘点行 Mapper。 */
@Mapper
public interface InventoryCountLineMapper extends BaseMapper<InventoryCountLine> {

  String COLUMNS =
      "id, count_id, line_no, item_id, lot_id, location_id, snapshot_qty, counted_qty,"
          + " variance_qty, reason";

  @Select(
      "SELECT " + COLUMNS + " FROM inventory_count_line WHERE count_id = #{countId} ORDER BY line_no")
  List<InventoryCountLine> findByCountId(@Param("countId") long countId);

  @Update(
      """
      UPDATE inventory_count_line
      SET counted_qty = #{countedQty}, variance_qty = #{varianceQty}, reason = #{reason}
      WHERE id = #{id} AND count_id = #{countId}
      """)
  int recordCount(
      @Param("id") long id,
      @Param("countId") long countId,
      @Param("countedQty") BigDecimal countedQty,
      @Param("varianceQty") BigDecimal varianceQty,
      @Param("reason") String reason);
}

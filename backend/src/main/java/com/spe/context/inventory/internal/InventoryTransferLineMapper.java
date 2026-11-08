package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 调拨行 Mapper。 */
@Mapper
public interface InventoryTransferLineMapper extends BaseMapper<InventoryTransferLine> {

  String COLUMNS = "id, transfer_id, line_no, item_id, lot_id, qty, uom, unit_cost, amount";

  @Select(
      "SELECT " + COLUMNS + " FROM inventory_transfer_line"
          + " WHERE transfer_id = #{transferId} ORDER BY line_no")
  List<InventoryTransferLine> findByTransferId(@Param("transferId") long transferId);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inventory_transfer_line WHERE transfer_id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + " ORDER BY transfer_id, line_no</script>")
  List<InventoryTransferLine> findByTransferIds(@Param("ids") Collection<Long> ids);
}

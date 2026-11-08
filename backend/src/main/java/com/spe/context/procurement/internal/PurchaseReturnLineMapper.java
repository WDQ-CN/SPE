package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 采购退货行 Mapper。 */
@Mapper
public interface PurchaseReturnLineMapper extends BaseMapper<PurchaseReturnLine> {

  String COLUMNS = "id, return_id, line_no, po_line_id, item_id, qty, uom, unit_cost, amount, lot_no, location_id";

  @Select("SELECT " + COLUMNS + " FROM purchase_return_line WHERE return_id = #{returnId}"
      + " ORDER BY line_no")
  List<PurchaseReturnLine> findByReturnId(@Param("returnId") long returnId);
}

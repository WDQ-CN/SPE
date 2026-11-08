package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 收货单行 Mapper。 */
@Mapper
public interface GoodsReceiptLineMapper extends BaseMapper<GoodsReceiptLine> {

  String COLUMNS = "id, gr_id, line_no, po_line_id, item_id, qty, uom, lot_no, location_id";

  @Select("SELECT " + COLUMNS + " FROM goods_receipt_line WHERE gr_id = #{grId} ORDER BY line_no")
  List<GoodsReceiptLine> findByGrId(@Param("grId") long grId);
}

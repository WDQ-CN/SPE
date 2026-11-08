package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** BOM 行 Mapper。 */
@Mapper
public interface BomLineMapper extends BaseMapper<BomLine> {

  String COLUMNS = "id, bom_id, line_no, component_item_id, qty, uom";

  @Select("SELECT " + COLUMNS + " FROM bom_line WHERE bom_id = #{bomId} ORDER BY line_no")
  List<BomLine> findByBomId(@Param("bomId") long bomId);
}

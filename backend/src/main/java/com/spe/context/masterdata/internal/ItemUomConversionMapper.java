package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 物料计量单位换算 Mapper。 */
@Mapper
public interface ItemUomConversionMapper extends BaseMapper<ItemUomConversion> {

  String COLUMNS = "id, item_id, from_uom, to_uom, numerator, denominator, version";

  @Select("SELECT " + COLUMNS + " FROM item_uom_conversion WHERE item_id = #{itemId}"
      + " ORDER BY from_uom, to_uom")
  List<ItemUomConversion> findByItemId(@Param("itemId") long itemId);

  @Select("SELECT " + COLUMNS + " FROM item_uom_conversion WHERE id = #{id} AND item_id = #{itemId}")
  Optional<ItemUomConversion> findByIdAndItem(@Param("id") long id, @Param("itemId") long itemId);

  @Select(
      "SELECT COUNT(*) FROM item_uom_conversion"
          + " WHERE item_id = #{itemId} AND from_uom = #{fromUom} AND to_uom = #{toUom}")
  long countByKey(
      @Param("itemId") long itemId,
      @Param("fromUom") String fromUom,
      @Param("toUom") String toUom);

  @Delete("DELETE FROM item_uom_conversion WHERE id = #{id} AND item_id = #{itemId}")
  int deleteByIdAndItem(@Param("id") long id, @Param("itemId") long itemId);
}

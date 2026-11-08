package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 库位容量 Mapper。 */
@Mapper
public interface LocationCapacityMapper extends BaseMapper<LocationCapacity> {

  String COLUMNS =
      "id, location_id, item_id, max_qty, uom, status, version, created_at";

  @Select("SELECT " + COLUMNS + " FROM location_capacity WHERE location_id = #{locationId}")
  List<LocationCapacity> findByLocation(@Param("locationId") long locationId);

  @Select(
      "SELECT " + COLUMNS + " FROM location_capacity WHERE location_id = #{locationId}"
          + " ORDER BY item_id FOR UPDATE")
  List<LocationCapacity> lockByLocation(@Param("locationId") long locationId);

  @Select(
      "SELECT " + COLUMNS + " FROM location_capacity"
          + " WHERE location_id = #{locationId} AND item_id = #{itemId}")
  Optional<LocationCapacity> find(
      @Param("locationId") long locationId, @Param("itemId") long itemId);

  @Update(
      """
      UPDATE location_capacity
      SET max_qty = #{maxQty}, uom = #{uom}, status = #{status}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int update(
      @Param("id") long id,
      @Param("maxQty") java.math.BigDecimal maxQty,
      @Param("uom") String uom,
      @Param("status") com.spe.context.masterdata.api.MasterDataStatus status,
      @Param("version") int version);
}

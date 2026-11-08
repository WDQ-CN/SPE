package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 仓库库位 Mapper。 */
@Mapper
public interface WarehouseLocationMapper extends BaseMapper<WarehouseLocation> {

  String COLUMNS =
      "id, warehouse_id, code, name, zone, aisle, rack, level_no, bin_no, status, version";

  @Select(
      "<script>SELECT " + COLUMNS + " FROM warehouse_location"
          + "<where>"
          + "<if test=\"warehouseId != null\">AND warehouse_id = #{warehouseId}</if>"
          + "<if test=\"status != null\">AND status = #{status}</if>"
          + "</where> ORDER BY warehouse_id, code</script>")
  List<WarehouseLocation> findByWarehouse(
      @Param("warehouseId") Long warehouseId, @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM warehouse_location WHERE id = #{id}")
  Optional<WarehouseLocation> findById(@Param("id") long id);

  @Select(
      "<script>SELECT " + COLUMNS + " FROM warehouse_location WHERE id IN"
          + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
          + "</script>")
  List<WarehouseLocation> findByIds(@Param("ids") Collection<Long> ids);

  @Select(
      "SELECT COUNT(*) FROM warehouse_location WHERE warehouse_id = #{warehouseId}"
          + " AND code = #{code}")
  long countByWarehouseAndCode(
      @Param("warehouseId") long warehouseId, @Param("code") String code);

  @Update(
      """
      UPDATE warehouse_location
      SET code = #{code}, name = #{name}, zone = #{zone}, aisle = #{aisle}, rack = #{rack},
          level_no = #{levelNo}, bin_no = #{binNo}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("name") String name,
      @Param("zone") String zone,
      @Param("aisle") String aisle,
      @Param("rack") String rack,
      @Param("levelNo") String levelNo,
      @Param("binNo") String binNo,
      @Param("version") int version);

  @Update(
      """
      UPDATE warehouse_location SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}

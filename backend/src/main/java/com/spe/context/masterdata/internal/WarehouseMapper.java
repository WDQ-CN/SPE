package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.WarehouseType;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 仓库主数据 Mapper。 */
@Mapper
public interface WarehouseMapper extends BaseMapper<Warehouse> {

  String COLUMNS = "id, plant_id, code, name, warehouse_type, status, version";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="plantId != null">AND plant_id = #{plantId}</if>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%') OR name LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM warehouse " + SEARCH_CONDITION
          + " ORDER BY plant_id, code LIMIT #{size} OFFSET #{offset}</script>")
  List<Warehouse> findPage(
      @Param("plantId") Long plantId,
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM warehouse " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("plantId") Long plantId,
      @Param("keyword") String keyword,
      @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM warehouse WHERE id = #{id}")
  Optional<Warehouse> findById(@Param("id") long id);

  @Select("SELECT COUNT(*) FROM warehouse WHERE plant_id = #{plantId} AND code = #{code}")
  long countByPlantAndCode(@Param("plantId") long plantId, @Param("code") String code);

  @Update(
      """
      UPDATE warehouse
      SET code = #{code}, name = #{name}, warehouse_type = #{warehouseType}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("name") String name,
      @Param("warehouseType") WarehouseType warehouseType,
      @Param("version") int version);

  @Update(
      """
      UPDATE warehouse
      SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}

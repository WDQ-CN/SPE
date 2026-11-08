package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spe.context.masterdata.api.ItemType;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.ValuationMethod;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 物料主数据 Mapper。 */
@Mapper
public interface ItemMapper extends BaseMapper<Item> {

  String COLUMNS =
      "id, code, name, spec, item_type, base_uom, lot_controlled, serial_controlled,"
          + " valuation_method, standard_cost, status, version";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="keyword != null and keyword != ''">
          AND (code LIKE CONCAT('%', #{keyword}, '%') OR name LIKE CONCAT('%', #{keyword}, '%')
               OR spec LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="itemType != null">AND item_type = #{itemType}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM item " + SEARCH_CONDITION
          + " ORDER BY code LIMIT #{size} OFFSET #{offset}</script>")
  List<Item> findPage(
      @Param("keyword") String keyword,
      @Param("itemType") ItemType itemType,
      @Param("status") MasterDataStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM item " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("keyword") String keyword,
      @Param("itemType") ItemType itemType,
      @Param("status") MasterDataStatus status);

  @Select("SELECT " + COLUMNS + " FROM item WHERE id = #{id}")
  Optional<Item> findById(@Param("id") long id);

  @Select(
      """
      <script>SELECT id, code, name, spec, item_type, base_uom, lot_controlled, serial_controlled,
                     valuation_method, standard_cost, status, version
      FROM item WHERE id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY id</script>
      """)
  List<Item> findByIds(@Param("ids") Collection<Long> ids);

  @Select("SELECT COUNT(*) FROM item WHERE code = #{code}")
  long countByCode(@Param("code") String code);

  @Update(
      """
      UPDATE item
      SET code = #{code}, name = #{name}, spec = #{spec}, item_type = #{itemType},
          base_uom = #{baseUom}, lot_controlled = #{lotControlled},
          serial_controlled = #{serialControlled}, valuation_method = #{valuationMethod},
          standard_cost = #{standardCost}, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateProfile(
      @Param("id") long id,
      @Param("code") String code,
      @Param("name") String name,
      @Param("spec") String spec,
      @Param("itemType") ItemType itemType,
      @Param("baseUom") String baseUom,
      @Param("lotControlled") boolean lotControlled,
      @Param("serialControlled") boolean serialControlled,
      @Param("valuationMethod") ValuationMethod valuationMethod,
      @Param("standardCost") java.math.BigDecimal standardCost,
      @Param("version") int version);

  @Update(
      """
      UPDATE item
      SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") MasterDataStatus target,
      @Param("expected") MasterDataStatus expected,
      @Param("version") int version);
}

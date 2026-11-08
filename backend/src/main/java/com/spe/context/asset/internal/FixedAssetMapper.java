package com.spe.context.asset.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 资产 Mapper。 */
@Mapper
public interface FixedAssetMapper extends BaseMapper<FixedAsset> {

  String COLUMNS =
      "id, asset_no, company_id, plant_id, category, name, source_type, source_id, cost,"
          + " salvage_value, useful_life_months, depreciation_method, accumulated_depreciation,"
          + " status, last_depreciation_period, capitalized_at, disposed_at, disposal_proceeds,"
          + " disposal_gain_loss, version, created_by, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM fixed_asset " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<FixedAsset> findPage(
      @Param("companyId") Long companyId,
      @Param("status") FixedAssetStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM fixed_asset " + SEARCH_CONDITION + "</script>")
  long countBy(@Param("companyId") Long companyId, @Param("status") FixedAssetStatus status);

  @Select("SELECT " + COLUMNS + " FROM fixed_asset WHERE id = #{id}")
  Optional<FixedAsset> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM fixed_asset WHERE id = #{id} FOR UPDATE")
  Optional<FixedAsset> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE fixed_asset
      SET status = 'ACTIVE', capitalized_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'PLANNED'
      """)
  int markCapitalized(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE fixed_asset
      SET accumulated_depreciation = #{accumulated}, last_depreciation_period = #{period},
          version = version + 1
      WHERE id = #{id} AND status = 'ACTIVE'
      """)
  int markDepreciated(
      @Param("id") long id,
      @Param("accumulated") BigDecimal accumulated,
      @Param("period") String period);

  @Update(
      """
      UPDATE fixed_asset
      SET status = 'DISPOSED', disposed_at = #{now}, disposal_proceeds = #{proceeds},
          disposal_gain_loss = #{gainLoss}, version = version + 1
      WHERE id = #{id} AND status = 'ACTIVE'
      """)
  int markDisposed(
      @Param("id") long id,
      @Param("now") LocalDateTime now,
      @Param("proceeds") BigDecimal proceeds,
      @Param("gainLoss") BigDecimal gainLoss);
}

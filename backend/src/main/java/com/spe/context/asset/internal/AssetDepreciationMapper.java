package com.spe.context.asset.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 折旧记录 Mapper。 */
@Mapper
public interface AssetDepreciationMapper extends BaseMapper<AssetDepreciation> {

  String COLUMNS = "id, asset_id, period, amount, accumulated_after, posted_at";

  @Select(
      "SELECT " + COLUMNS + " FROM asset_depreciation WHERE asset_id = #{assetId}"
          + " ORDER BY period")
  List<AssetDepreciation> findByAssetId(@Param("assetId") long assetId);
}

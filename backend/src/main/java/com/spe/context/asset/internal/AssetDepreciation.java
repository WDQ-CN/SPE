package com.spe.context.asset.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 资产折旧记录。 */
@Data
@TableName("asset_depreciation")
public class AssetDepreciation {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long assetId;
  private String period;
  private BigDecimal amount;
  private BigDecimal accumulatedAfter;
  private LocalDateTime postedAt;
}

package com.spe.context.asset.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 固定资产卡片（V1028）。 */
@Data
@TableName("fixed_asset")
public class FixedAsset {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String assetNo;
  private Long companyId;
  private Long plantId;
  private String category;
  private String name;
  private String sourceType;
  private Long sourceId;
  private BigDecimal cost;
  private BigDecimal salvageValue;
  private Integer usefulLifeMonths;
  private String depreciationMethod;
  private BigDecimal accumulatedDepreciation;
  private FixedAssetStatus status;
  private String lastDepreciationPeriod;
  private LocalDateTime capitalizedAt;
  private LocalDateTime disposedAt;
  private BigDecimal disposalProceeds;
  private BigDecimal disposalGainLoss;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
}

package com.spe.context.asset.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 资产详情投影。 */
public record FixedAssetView(
    long id,
    String assetNo,
    long companyId,
    Long plantId,
    String category,
    String name,
    String sourceType,
    Long sourceId,
    BigDecimal cost,
    BigDecimal salvageValue,
    int usefulLifeMonths,
    String depreciationMethod,
    BigDecimal accumulatedDepreciation,
    BigDecimal netBookValue,
    FixedAssetStatus status,
    String lastDepreciationPeriod,
    LocalDateTime capitalizedAt,
    LocalDateTime disposedAt,
    BigDecimal disposalProceeds,
    BigDecimal disposalGainLoss,
    int version,
    List<Depreciation> depreciations) {

  public record Depreciation(
      long id, String period, BigDecimal amount, BigDecimal accumulatedAfter, LocalDateTime postedAt) {}
}

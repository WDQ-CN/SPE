package com.spe.context.asset.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.asset.api.events.AssetCapitalizedEvent;
import com.spe.context.asset.api.events.AssetDepreciatedEvent;
import com.spe.context.asset.api.events.AssetDisposedEvent;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 固定资产：卡片 → 资本化 → 直线折旧 → 处置（docs/erp/38 §3）。 */
@Service
@RequiredArgsConstructor
public class FixedAssetService {

  private final FixedAssetMapper assetMapper;
  private final AssetDepreciationMapper depreciationMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public FixedAssetView create(
      long operatorId,
      long companyId,
      Long plantId,
      String category,
      String name,
      String sourceType,
      Long sourceId,
      BigDecimal cost,
      BigDecimal salvageValue,
      Integer usefulLifeMonths,
      String method) {
    CompanyBrief company =
        masterDataQueryFacade
            .findCompany(companyId)
            .filter(CompanyBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    if (plantId != null) {
      masterDataQueryFacade
          .findPlant(plantId)
          .filter(plant -> plant.active() && plant.companyId() == companyId)
          .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    }
    if (category == null || category.isBlank() || name == null || name.isBlank()) {
      throw new BizException(ErrorCode.ASSET_DISPOSAL_INVALID, "资产类别与名称不能为空");
    }
    BigDecimal normalizedCost = cost == null ? BigDecimal.ZERO : cost;
    BigDecimal salvage = salvageValue == null ? BigDecimal.ZERO : salvageValue;
    if (normalizedCost.signum() <= 0
        || salvage.signum() < 0
        || salvage.compareTo(normalizedCost) >= 0
        || usefulLifeMonths == null
        || usefulLifeMonths <= 0) {
      throw new BizException(ErrorCode.ASSET_DEPRECIATION_INVALID, "原值/残值/寿命不合法");
    }
    String normalizedMethod = method == null || method.isBlank() ? "STRAIGHT_LINE" : method.trim().toUpperCase();
    if (!"STRAIGHT_LINE".equals(normalizedMethod)) {
      throw new BizException(ErrorCode.ASSET_DEPRECIATION_INVALID, "E5 仅支持直线折旧");
    }
    FixedAsset asset = new FixedAsset();
    asset.setAssetNo(AssetSupport.assetNo());
    asset.setCompanyId(companyId);
    asset.setPlantId(plantId);
    asset.setCategory(category.trim());
    asset.setName(name.trim());
    asset.setSourceType(
        sourceType == null || sourceType.isBlank() ? "MANUAL" : sourceType.trim().toUpperCase());
    asset.setSourceId(sourceId);
    asset.setCost(normalizedCost.setScale(4, RoundingMode.HALF_UP));
    asset.setSalvageValue(salvage.setScale(4, RoundingMode.HALF_UP));
    asset.setUsefulLifeMonths(usefulLifeMonths);
    asset.setDepreciationMethod(normalizedMethod);
    asset.setAccumulatedDepreciation(BigDecimal.ZERO);
    asset.setStatus(FixedAssetStatus.PLANNED);
    asset.setVersion(0);
    asset.setCreatedBy(operatorId);
    assetMapper.insert(asset);
    auditFacade.record(
        AuditAction.ASSET_CREATE,
        AuditTargetType.FIXED_ASSET,
        asset.getId(),
        Map.of("assetNo", asset.getAssetNo(), "cost", asset.getCost(), "life", usefulLifeMonths));
    return detail(asset.getId());
  }

  @Transactional
  public FixedAssetView capitalize(long id, long operatorId) {
    FixedAsset asset = lock(id);
    if (assetMapper.markCapitalized(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.ASSET_STATUS_INVALID);
    }
    String currency = currencyOf(asset.getCompanyId());
    eventPublisher.publish(
        new AssetCapitalizedEvent(
            id, asset.getCompanyId(), asset.getCost(), currency, LocalDate.now(ZoneOffset.UTC)));
    auditFacade.record(
        AuditAction.ASSET_CAPITALIZE,
        AuditTargetType.FIXED_ASSET,
        id,
        Map.of("assetNo", asset.getAssetNo(), "cost", asset.getCost()));
    return detail(id);
  }

  @Transactional
  public FixedAssetView depreciate(long id, String period) {
    FixedAsset asset = lock(id);
    if (asset.getStatus() != FixedAssetStatus.ACTIVE) {
      throw new BizException(ErrorCode.ASSET_STATUS_INVALID);
    }
    if (period == null || !period.matches("\\d{4}-(0[1-9]|1[0-2])")) {
      throw new BizException(ErrorCode.ASSET_DEPRECIATION_INVALID, "期间格式必须为 YYYY-MM");
    }
    if (asset.getLastDepreciationPeriod() != null
        && period.compareTo(asset.getLastDepreciationPeriod()) <= 0) {
      throw new BizException(ErrorCode.ASSET_DEPRECIATION_INVALID, "折旧期间必须递增且不能重复");
    }
    BigDecimal depreciable = asset.getCost().subtract(asset.getSalvageValue());
    BigDecimal remaining = depreciable.subtract(asset.getAccumulatedDepreciation());
    if (remaining.signum() <= 0) {
      throw new BizException(ErrorCode.ASSET_DEPRECIATION_INVALID, "资产已提足折旧");
    }
    BigDecimal monthly =
        depreciable.divide(
            BigDecimal.valueOf(asset.getUsefulLifeMonths()), 4, RoundingMode.HALF_UP);
    BigDecimal amount = monthly.min(remaining);
    BigDecimal accumulated = asset.getAccumulatedDepreciation().add(amount);
    AssetDepreciation record = new AssetDepreciation();
    record.setAssetId(id);
    record.setPeriod(period);
    record.setAmount(amount);
    record.setAccumulatedAfter(accumulated);
    depreciationMapper.insert(record);
    if (assetMapper.markDepreciated(id, accumulated, period) == 0) {
      throw new BizException(ErrorCode.ASSET_STATUS_INVALID);
    }
    String currency = currencyOf(asset.getCompanyId());
    eventPublisher.publish(
        new AssetDepreciatedEvent(
            id,
            record.getId(),
            asset.getCompanyId(),
            period,
            amount,
            accumulated,
            currency,
            LocalDate.now(ZoneOffset.UTC)));
    auditFacade.record(
        AuditAction.ASSET_DEPRECIATE,
        AuditTargetType.FIXED_ASSET,
        id,
        Map.of("assetNo", asset.getAssetNo(), "period", period, "amount", amount));
    return detail(id);
  }

  @Transactional
  public FixedAssetView dispose(long id, BigDecimal proceeds) {
    FixedAsset asset = lock(id);
    if (asset.getStatus() != FixedAssetStatus.ACTIVE) {
      throw new BizException(ErrorCode.ASSET_STATUS_INVALID);
    }
    BigDecimal normalizedProceeds = proceeds == null ? BigDecimal.ZERO : proceeds;
    if (normalizedProceeds.signum() < 0) {
      throw new BizException(ErrorCode.ASSET_DISPOSAL_INVALID, "处置收入不能为负");
    }
    BigDecimal netBook = asset.getCost().subtract(asset.getAccumulatedDepreciation());
    BigDecimal gainLoss = normalizedProceeds.subtract(netBook);
    if (assetMapper.markDisposed(
            id, LocalDateTime.now(ZoneOffset.UTC), normalizedProceeds, gainLoss)
        == 0) {
      throw new BizException(ErrorCode.ASSET_STATUS_INVALID);
    }
    String currency = currencyOf(asset.getCompanyId());
    eventPublisher.publish(
        new AssetDisposedEvent(
            id,
            asset.getCompanyId(),
            asset.getCost(),
            asset.getAccumulatedDepreciation(),
            normalizedProceeds,
            gainLoss,
            currency,
            LocalDate.now(ZoneOffset.UTC)));
    auditFacade.record(
        AuditAction.ASSET_DISPOSE,
        AuditTargetType.FIXED_ASSET,
        id,
        Map.of(
            "assetNo", asset.getAssetNo(),
            "proceeds", normalizedProceeds,
            "gainLoss", gainLoss));
    return detail(id);
  }

  @Transactional(readOnly = true)
  public PageResult<FixedAsset> page(int page, int size, Long companyId, String status) {
    PageQuery query = PageQuery.of(page, size);
    FixedAssetStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseStatus(status);
    return PageResult.of(
        assetMapper.findPage(companyId, parsed, query.offset(), query.size()),
        assetMapper.countBy(companyId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public FixedAssetView detail(long id) {
    FixedAsset asset =
        assetMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.ASSET_NOT_FOUND));
    return new FixedAssetView(
        asset.getId(),
        asset.getAssetNo(),
        asset.getCompanyId(),
        asset.getPlantId(),
        asset.getCategory(),
        asset.getName(),
        asset.getSourceType(),
        asset.getSourceId(),
        asset.getCost(),
        asset.getSalvageValue(),
        asset.getUsefulLifeMonths(),
        asset.getDepreciationMethod(),
        asset.getAccumulatedDepreciation(),
        asset.getCost().subtract(asset.getAccumulatedDepreciation()),
        asset.getStatus(),
        asset.getLastDepreciationPeriod(),
        asset.getCapitalizedAt(),
        asset.getDisposedAt(),
        asset.getDisposalProceeds(),
        asset.getDisposalGainLoss(),
        asset.getVersion(),
        depreciationMapper.findByAssetId(id).stream()
            .map(
                row ->
                    new FixedAssetView.Depreciation(
                        row.getId(),
                        row.getPeriod(),
                        row.getAmount(),
                        row.getAccumulatedAfter(),
                        row.getPostedAt()))
            .toList());
  }

  private FixedAsset lock(long id) {
    return assetMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.ASSET_NOT_FOUND));
  }

  private String currencyOf(long companyId) {
    return masterDataQueryFacade
        .findCompany(companyId)
        .map(CompanyBrief::baseCurrency)
        .orElse("CNY");
  }

  private static FixedAssetStatus parseStatus(String status) {
    try {
      return FixedAssetStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "资产状态不合法：" + status);
    }
  }
}

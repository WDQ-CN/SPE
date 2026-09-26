package com.spe.context.template.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import com.spe.context.template.api.TemplateVersionStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TemplateSnapshotFacade} 的实现（05 §5.1 / 05 ADR-3）：只读跨上下文查询，实体不出上下文。
 *
 * <p><b>只认已发布版本</b>：草稿一律按"没有这个快照"处理。这不是防御性编程，而是 05 ADR-3 的核心——
 * 计分口径必须来自冻结过的数据，否则任务算到一半、模板被改，历史结果就没有恒定口径可言。
 */
@Component
@RequiredArgsConstructor
public class TemplateSnapshotFacadeImpl implements TemplateSnapshotFacade {

  private final TemplateMapper templateMapper;
  private final TemplateVersionMapper templateVersionMapper;
  private final DimensionMapper dimensionMapper;
  private final IndicatorMapper indicatorMapper;

  @Override
  @Transactional(readOnly = true)
  public Optional<TemplateSnapshot> findPublished(long versionId) {
    return templateVersionMapper
        .findById(versionId)
        .filter(version -> version.getStatus() == TemplateVersionStatus.PUBLISHED)
        .map(this::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public TemplateSnapshot requirePublished(long versionId) {
    return findPublished(versionId)
        .orElseThrow(
            () ->
                new BizException(
                    ErrorCode.TEMPLATE_VERSION_NOT_FOUND, "模板版本不存在或尚未发布：" + versionId));
  }

  private TemplateSnapshot toSnapshot(TemplateVersion version) {
    Template template =
        templateMapper
            .findById(version.getTemplateId())
            .orElseThrow(() -> new BizException(ErrorCode.TEMPLATE_NOT_FOUND));

    // 与 TemplateService.detail 同构：两条查询 + 内存分组，不逐维度回查
    List<Dimension> dimensions = dimensionMapper.findByVersionId(version.getId());
    Map<Long, List<Indicator>> byDimension =
        indicatorMapper.findByVersionId(version.getId()).stream()
            .collect(Collectors.groupingBy(Indicator::getDimensionId));

    List<DimensionSnapshot> dimensionSnapshots =
        dimensions.stream()
            .map(
                dimension ->
                    new DimensionSnapshot(
                        dimension.getId(),
                        dimension.getName(),
                        dimension.getWeight(),
                        Boolean.TRUE.equals(dimension.getIsKey()),
                        dimension.getSortNo() == null ? 0 : dimension.getSortNo(),
                        dimension.getSuggestedDept(),
                        byDimension.getOrDefault(dimension.getId(), List.of()).stream()
                            .map(TemplateSnapshotFacadeImpl::toIndicatorSnapshot)
                            .toList()))
            .toList();

    return new TemplateSnapshot(
        version.getId(),
        template.getId(),
        template.getName(),
        version.getVersionNo(),
        dimensionSnapshots);
  }

  private static IndicatorSnapshot toIndicatorSnapshot(Indicator indicator) {
    return new IndicatorSnapshot(
        indicator.getId(),
        indicator.getCode(),
        indicator.getName(),
        indicator.getWeight(),
        indicator.getType(),
        indicator.getScoreMode(),
        indicator.getUnit(),
        indicator.getTargetValue(),
        indicator.getStandard());
  }
}

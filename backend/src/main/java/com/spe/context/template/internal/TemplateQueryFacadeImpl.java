package com.spe.context.template.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.context.template.api.PublishedVersionBrief;
import com.spe.context.template.api.TemplateBrief;
import com.spe.context.template.api.TemplateQueryFacade;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TemplateQueryFacade} 的实现（12 §E16）：P3 建任务时"选模板 + 选版本"的数据来源。
 *
 * <p>全程只读，实体不出上下文。两条查询就够的地方不写三条：列表先聚合出"哪些模板有已发布版本"，
 * 再用一次 `IN` 查询把各模板的最新版本取回来——**不做逐模板回查**。
 */
@Component
@RequiredArgsConstructor
public class TemplateQueryFacadeImpl implements TemplateQueryFacade {

  private final TemplateMapper templateMapper;
  private final TemplateVersionMapper templateVersionMapper;

  @Override
  @Transactional(readOnly = true)
  public PageResult<TemplateBrief> listPublishable(PageQuery query) {
    List<TemplateRow> rows = templateMapper.findPublishablePage(query.offset(), query.size());
    if (rows.isEmpty()) {
      return PageResult.of(List.of(), templateMapper.countPublishable(), query.page(), query.size());
    }

    List<Long> templateIds = rows.stream().map(TemplateRow::getId).toList();
    // 已按 template_id, version_no DESC 排好序：每个模板的第一条就是它最新的已发布版本
    Map<Long, PublishedVersionBrief> latestByTemplate = new LinkedHashMap<>();
    for (TemplateVersion version : templateVersionMapper.findPublishedByTemplateIds(templateIds)) {
      latestByTemplate.putIfAbsent(version.getTemplateId(), toBrief(version));
    }

    List<TemplateBrief> briefs =
        rows.stream()
            .map(row -> new TemplateBrief(row.getId(), row.getName(), latestByTemplate.get(row.getId())))
            .toList();
    return PageResult.of(briefs, templateMapper.countPublishable(), query.page(), query.size());
  }

  @Override
  @Transactional(readOnly = true)
  public List<PublishedVersionBrief> publishedVersions(long templateId) {
    return templateVersionMapper.findPublishedByTemplateId(templateId).stream()
        .map(TemplateQueryFacadeImpl::toBrief)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PublishedVersionBrief> latestPublished(long templateId) {
    return templateVersionMapper.findPublishedByTemplateId(templateId).stream()
        .findFirst()
        .map(TemplateQueryFacadeImpl::toBrief);
  }

  private static PublishedVersionBrief toBrief(TemplateVersion version) {
    return new PublishedVersionBrief(
        version.getId(), version.getVersionNo(), version.getPublishedAt());
  }
}

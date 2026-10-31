package com.spe.context.template.internal;

import com.spe.context.template.api.PublishedVersionBrief;
import com.spe.context.template.api.TemplateQueryFacade;
import java.util.List;
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

  private final TemplateVersionMapper templateVersionMapper;

  @Override
  @Transactional(readOnly = true)
  public List<PublishedVersionBrief> publishedVersions(long templateId) {
    return templateVersionMapper.findPublishedByTemplateId(templateId).stream()
        .map(TemplateQueryFacadeImpl::toBrief)
        .toList();
  }

  private static PublishedVersionBrief toBrief(TemplateVersion version) {
    return new PublishedVersionBrief(
        version.getId(), version.getVersionNo(), version.getPublishedAt());
  }
}

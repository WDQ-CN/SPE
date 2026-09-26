package com.spe.context.template.api;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * 模板只读门面（12 §E16）：跨上下文读取模板与版本清单的唯一入口，实体与 Mapper 不外泄。
 *
 * <p>它的存在理由很具体：**P3 没有它就没法建任务**——01 J1 要求"选已发布版本"，而 05 §5.1 原先只登记了
 * {@link TemplateSnapshotFacade}（那是给计分取快照用的）。写操作不经此门面：模板维护只由 template 自己的
 * Controller 触发。
 */
public interface TemplateQueryFacade {

  /** 可分页选用的模板（**只含至少有一个已发布版本的**），按模板 id 升序。 */
  PageResult<TemplateBrief> listPublishable(PageQuery query);

  /** 某模板的全部已发布版本，版本号降序（最新的在前）；模板不存在时返回空列表。 */
  List<PublishedVersionBrief> publishedVersions(long templateId);

  /** 某模板的最新已发布版本；没有任何已发布版本时返回空。 */
  Optional<PublishedVersionBrief> latestPublished(long templateId);
}

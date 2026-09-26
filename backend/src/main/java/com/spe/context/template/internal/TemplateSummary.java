package com.spe.context.template.internal;

/**
 * 模板列表行（HTTP 响应投影，12 §E15 / §R-15）。
 *
 * <p>由 Mapper 的聚合结果 {@link TemplateRow} 手工转换而来（项目里 record 一律手工转换，不走 MyBatis 的
 * 结果集自动映射）。这些字段正是前端列表要展示的："模板名 + 版本数 + 最新已发布版本号 + 是否有草稿"。
 *
 * @param id 模板 id
 * @param name 模板名称
 * @param versionCount 版本数（含草稿）
 * @param draftCount 草稿数
 * @param latestPublishedNo 最新已发布版本号；一个都没发布过时为 {@code null}
 * @param latestVersionId 最新版本（不分状态）的 id，供前端直接进入编辑器
 */
public record TemplateSummary(
    long id,
    String name,
    long versionCount,
    long draftCount,
    Integer latestPublishedNo,
    Long latestVersionId) {

  public boolean hasDraft() {
    return draftCount > 0;
  }

  static TemplateSummary of(TemplateRow row) {
    return new TemplateSummary(
        row.getId(),
        row.getName(),
        row.getVersionCount() == null ? 0L : row.getVersionCount(),
        row.getDraftCount() == null ? 0L : row.getDraftCount(),
        row.getLatestPublishedNo(),
        row.getLatestVersionId());
  }
}

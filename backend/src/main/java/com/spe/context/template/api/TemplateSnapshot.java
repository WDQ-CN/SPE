package com.spe.context.template.api;

import java.util.List;

/**
 * 模板快照（12 §E16 / 05 ADR-3）：**已发布版本**的不可变视图，即 P3 的 {@code ScoreCalculator} 的输入。
 *
 * <p>「发布即冻结」的兑现方式就是它：维度与指标在发布那一刻被复制成一份不再变化的数据，此后无论草稿
 * 怎么改、新版本怎么发，读历史任务的计分口径都恒定（05 ADR-3 的理由）。
 *
 * @param versionId 版本 id（任务绑定的是它，而不是模板 id）
 * @param templateId 模板 id
 * @param templateName 模板名称
 * @param versionNo 版本号（模板内从 1 递增）
 * @param dimensions 维度树（按 {@code sort_no} 排序）
 */
public record TemplateSnapshot(
    long versionId,
    long templateId,
    String templateName,
    int versionNo,
    List<DimensionSnapshot> dimensions) {
}

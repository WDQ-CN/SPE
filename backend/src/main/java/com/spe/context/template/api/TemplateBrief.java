package com.spe.context.template.api;

/**
 * 模板简要信息（12 §E16）：P3 创建任务时"选模板"的选项来源。
 *
 * <p>刻意只带"能否被选用"所需的信息——任务必须绑定已发布版本（01 J1），所以这里直接给出该模板的
 * **最新已发布版本**；没有任何已发布版本的模板不会出现在选择器里。
 */
public record TemplateBrief(long templateId, String name, PublishedVersionBrief latestPublished) {
}

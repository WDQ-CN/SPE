package com.spe.context.template.internal;

import com.spe.context.template.api.TemplateVersionStatus;
import java.util.List;

/**
 * 版本详情（HTTP 响应投影）：编辑器需要的整棵树。
 *
 * <p>`version` 是**版本自身的**乐观锁版本：发布时必须回传它（12 §E12 的双守卫）。
 */
public record TemplateVersionTree(
    long templateId,
    String templateName,
    long versionId,
    int versionNo,
    TemplateVersionStatus status,
    int version,
    List<DimensionView> dimensions) {
}

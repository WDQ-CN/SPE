package com.spe.context.template.api;

import java.time.LocalDateTime;

/**
 * 已发布版本摘要（12 §E16）。
 *
 * <p>P3 建任务时选的就是它（01 J1：任务必须绑定**已发布版本**）。时间用 {@link LocalDateTime} 与
 * `template_version.published_at DATETIME(3)` 直连，不经过时区换算（连接侧已钉 `connectionTimeZone=UTC`）。
 */
public record PublishedVersionBrief(long versionId, int versionNo, LocalDateTime publishedAt) {
}

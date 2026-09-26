package com.spe.context.template.api;

/**
 * 模板版本状态（02 §3「草稿/已发布/废弃」、06 §2.1 的状态常量）。
 *
 * <p>P2 只实现 {@code DRAFT → PUBLISHED} 一条迁移（12 §E10）：{@code ARCHIVED} 按 07 §4「枚举值预留、
 * 入口后置」的取向先留着——**当前没有任何端点能进入该状态**，02 §3 也没规定"废弃"的进入条件。
 */
public enum TemplateVersionStatus {
  DRAFT,
  PUBLISHED,
  ARCHIVED
}

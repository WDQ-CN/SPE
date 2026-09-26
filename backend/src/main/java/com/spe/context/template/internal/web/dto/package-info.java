/**
 * 模板写端点的请求 DTO（02 §4 的模板行）。
 *
 * <p>与 `internal/web` 的分工：DTO 只负责**入口形状与单行格式校验**（长度、范围、精度，且逐条与 `V7`
 * 的列定义对齐），业务规则（权重合计、编码唯一、状态迁移）一律留在 `TemplateService`。
 *
 * <p>放 `internal` 之下而不是 `api`：它们是本上下文的 HTTP 实现细节，跨上下文的调用走 `api` 门面
 * （DTO 与实体都不出上下文，05 §4.3 / ADR-10）。
 */
package com.spe.context.template.internal.web.dto;

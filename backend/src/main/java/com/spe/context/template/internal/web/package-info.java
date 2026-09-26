/**
 * template 上下文的 HTTP 实现面（Controller + 请求 DTO）。
 *
 * <p>两个 Controller 按**权限边界**拆分：{@code TemplateController} 类级 `hasRole('ADMIN')`（写）、
 * {@code TemplateQueryController} 类级 `hasAnyRole('ADMIN','MANAGER')`（读，01 §7 给 MANAGER 只读视图）。
 * 一个类只对应一条边界，审起来一眼能看出「这个类的**所有**方法都要求什么角色」，也不必在同一个类里
 * 用方法级注解去覆盖类级（ArchUnit R-H 要求 Controller 存在类级 `@PreAuthorize`）。
 *
 * <p>按 06 §1.1，`web/` 位于 `internal/` **之下**，因此 R-A「不得依赖其他上下文的 internal」自动覆盖它，
 * 不必为 web 单列例外。
 */
package com.spe.context.template.internal.web;

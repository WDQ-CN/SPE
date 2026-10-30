/**
 * 历史占位（P0）。M6 的 Token 拦截器与校验落在 `system.internal.security` / `system.internal.token`：
 * Token 表属 system，JWT 过滤器也在 system，鉴权放同一安全链最一致（`03 §45` M6R-03）。本包暂不承载实现。
 */
package com.spe.context.openapi.internal.token;

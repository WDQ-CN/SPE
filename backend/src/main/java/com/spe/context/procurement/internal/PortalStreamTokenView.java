package com.spe.context.procurement.internal;

import java.time.LocalDateTime;

/** SSE 短令牌响应：仅用于 EventSource 的 query 参数，5 分钟一次性。 */
public record PortalStreamTokenView(String token, LocalDateTime expiresAt) {}

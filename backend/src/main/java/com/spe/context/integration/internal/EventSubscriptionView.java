package com.spe.context.integration.internal;

import java.time.LocalDateTime;

/** 订阅投影（不返回密钥）。 */
public record EventSubscriptionView(
    long id,
    long companyId,
    String url,
    String eventTypes,
    SubscriptionStatus status,
    LocalDateTime createdAt) {}

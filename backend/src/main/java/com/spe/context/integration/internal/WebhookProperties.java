package com.spe.context.integration.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Webhook 目标策略（application.yml 的 app.integration.webhook）。 */
@ConfigurationProperties(prefix = "app.integration.webhook")
public record WebhookProperties(boolean allowPrivateTargets) {}

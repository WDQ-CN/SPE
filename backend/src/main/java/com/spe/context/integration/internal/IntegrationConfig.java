package com.spe.context.integration.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 集成上下文的配置绑定入口。 */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class IntegrationConfig {}

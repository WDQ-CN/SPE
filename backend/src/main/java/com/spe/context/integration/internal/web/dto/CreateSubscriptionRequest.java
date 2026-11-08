package com.spe.context.integration.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 创建 Webhook 订阅。 */
public record CreateSubscriptionRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotBlank(message = "URL 不能为空") @Size(max = 500) String url,
    @NotEmpty(message = "至少订阅一个事件类型") List<@NotBlank @Size(max = 64) String> eventTypes,
    @Size(max = 128, message = "密钥长度不得超过 128") String secret) {}

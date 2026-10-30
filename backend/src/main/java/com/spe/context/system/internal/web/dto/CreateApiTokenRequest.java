package com.spe.context.system.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 生成开放 API Token（M6）：只填名称；明文由服务端生成并只在响应返回一次。 */
public record CreateApiTokenRequest(
    @NotBlank(message = "Token 名称不能为空") @Size(max = 100, message = "Token 名称不得超过 100 字")
        String name) {}

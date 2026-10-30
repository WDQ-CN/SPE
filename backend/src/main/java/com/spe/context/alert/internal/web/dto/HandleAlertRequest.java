package com.spe.context.alert.internal.web.dto;

import jakarta.validation.constraints.Size;

/** 处理预警的入参（M5 W3）：备注可选，最长 500。 */
public record HandleAlertRequest(
    @Size(max = 500, message = "处理备注不得超过 500 字") String comment) {}

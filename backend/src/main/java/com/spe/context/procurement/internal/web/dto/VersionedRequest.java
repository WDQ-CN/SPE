package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** 只带版本号的状态动作（提交/发送/取消/通过）。 */
public record VersionedRequest(@PositiveOrZero(message = "版本号不能为负") int version) {}

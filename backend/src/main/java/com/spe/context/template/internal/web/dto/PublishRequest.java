package com.spe.context.template.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 发布版本（02 §4 `POST /api/template-versions/{id}/publish`）。
 *
 * <p>`version` 必填：发布是**守卫 UPDATE**（`WHERE status='DRAFT' AND version=?`，12 §E12），
 * 状态竞争与版本竞争都由它兜住，0 行 → 3001。
 */
public record PublishRequest(
    @NotNull(message = "版本号不能为空") @Min(value = 0, message = "版本号不能为负") Integer version) {}

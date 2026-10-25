package com.spe.context.evaluation.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 审批通过（02 §4 / 15 §E5）：带乐观锁版本；节点已流转或版本不符 → 3xxx。 */
public record ApproveRequest(
    @NotNull(message = "缺少乐观锁版本") @Min(value = 0, message = "版本号不能为负") Integer version) {}

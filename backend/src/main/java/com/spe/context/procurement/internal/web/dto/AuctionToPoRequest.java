package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.NotNull;

/** 竞价结果转采购订单。 */
public record AuctionToPoRequest(@NotNull(message = "工厂不能为空") Long plantId) {}

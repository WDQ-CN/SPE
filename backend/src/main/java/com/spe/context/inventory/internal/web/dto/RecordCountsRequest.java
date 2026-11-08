package com.spe.context.inventory.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 批量录入实盘。 */
public record RecordCountsRequest(
    @NotEmpty(message = "实盘数据不能为空") @Valid List<RecordCountLineRequest> lines) {}

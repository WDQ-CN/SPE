package com.spe.context.masterdata.internal.web.dto;

import com.spe.context.masterdata.api.ItemType;
import com.spe.context.masterdata.api.ValuationMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 编辑物料。 */
public record UpdateItemRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 50, message = "编码长度不得超过 50") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 200, message = "名称长度不得超过 200") String name,
    @Size(max = 255, message = "规格长度不得超过 255") String spec,
    @NotNull(message = "物料类型不能为空") ItemType itemType,
    @NotBlank(message = "基本计量单位不能为空")
        @Size(max = 16, message = "计量单位长度不得超过 16")
        String baseUom,
    Boolean lotControlled,
    Boolean serialControlled,
    @NotNull(message = "估值方法不能为空") ValuationMethod valuationMethod,
    @DecimalMin(value = "0.00", message = "标准成本不能为负") BigDecimal standardCost,
    @NotNull(message = "版本不能为空") @Min(value = 0, message = "版本不能为负") Integer version) {}

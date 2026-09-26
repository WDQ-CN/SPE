package com.spe.context.supplier.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编辑供应商入参（02 §4 `PUT /suppliers/{id}`）。
 *
 * <p>`code` 可改（E17）、`version` 必填（守卫 UPDATE 的前置条件，06 §2.4）、`category` 可为空（E10）。
 */
public record UpdateSupplierRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 50, message = "编码长度不得超过 50") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 100, message = "名称长度不得超过 100") String name,
    @Size(max = 50, message = "分类长度不得超过 50") String category,
    @NotNull(message = "版本号不能为空") @Min(value = 0, message = "版本号不能为负") Integer version) {}

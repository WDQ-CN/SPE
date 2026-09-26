package com.spe.context.supplier.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增供应商入参（01 S2 / 02 §4 `POST /suppliers`）。
 *
 * <p>长度上限与 `V3__supplier.sql` 的列长度**逐一对齐**：入口不拦，超长值要到 INSERT 才报错并漏成 5xxx
 * （06 §2.6 的红线）。`category` 可空（E10/V6），故只限长度、不要求非空。
 */
public record CreateSupplierRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 50, message = "编码长度不得超过 50") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 100, message = "名称长度不得超过 100") String name,
    @Size(max = 50, message = "分类长度不得超过 50") String category) {}

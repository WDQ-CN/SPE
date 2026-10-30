package com.spe.context.evaluation.api.openapi;

/** 开放 API 任务里的供应商行（M6）：只暴露外部系统映射所需的 code/name/状态。 */
public record OpenApiSupplierView(
    long supplierId, String supplierCode, String supplierName, String status) {}

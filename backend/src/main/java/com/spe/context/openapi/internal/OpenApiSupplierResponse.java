package com.spe.context.openapi.internal;

/** 开放 API 任务里的供应商行（openapi 本地响应 DTO，Controller 不跨上下文依赖 evaluation.api）。 */
public record OpenApiSupplierResponse(
    long supplierId, String supplierCode, String supplierName, String status) {}

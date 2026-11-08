package com.spe.context.manufacturing.internal;

import java.math.BigDecimal;

/** 工单组件投影。 */
public record WorkOrderComponentView(
    long id,
    int lineNo,
    long componentItemId,
    String componentCode,
    String componentName,
    BigDecimal qtyRequired,
    BigDecimal issuedQty,
    String uom) {}

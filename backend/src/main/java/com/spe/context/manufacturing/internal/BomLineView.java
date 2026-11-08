package com.spe.context.manufacturing.internal;

import java.math.BigDecimal;

/** BOM 行投影。 */
public record BomLineView(
    long id,
    int lineNo,
    long componentItemId,
    String componentCode,
    String componentName,
    BigDecimal qty,
    String uom) {}

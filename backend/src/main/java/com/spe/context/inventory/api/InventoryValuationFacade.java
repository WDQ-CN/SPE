package com.spe.context.inventory.api;

import java.util.List;

/** 库存估值只读门面（05 §8.3）：财务对账用，不暴露库存实体。 */
public interface InventoryValuationFacade {

  List<InventoryStockValuation> valuations(long companyId);
}

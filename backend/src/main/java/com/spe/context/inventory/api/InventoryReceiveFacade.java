package com.spe.context.inventory.api;

import java.util.List;

/** 库存入库门面（销售退货/生产完工等）；失败回滚调用方事务。 */
public interface InventoryReceiveFacade {

  void receive(List<InventoryReceiveCommand> commands);
}

package com.spe.context.inventory.api;

import java.util.List;

/** 库存出库门面（销售发货等跨上下文调用）；失败抛出业务异常并回滚调用方事务。 */
public interface InventoryIssueFacade {

  List<InventoryIssueResult> issue(List<InventoryIssueCommand> commands);
}

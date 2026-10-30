package com.spe.context.alert.internal;

import com.spe.context.evaluation.api.events.TaskSupplierUnlockedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 评估单被解锁重评 → 该供应商该周期的活动预警置 SUPERSEDED（01 R5/W2；22 §2 D4）。
 *
 * <p>重评期间不保留旧结果的预警；重新定稿后由 {@link AlertListener} 按最新结果重算。
 * 与解锁事务同步：失败会整体回滚。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnlockedAlertListener {

  private final AlertMapper alertMapper;

  @EventListener
  public void onTaskSupplierUnlocked(TaskSupplierUnlockedEvent event) {
    int superseded =
        alertMapper.supersedeActive(event.supplierId(), event.year(), event.quarter());
    log.info(
        "评估单 {} 解锁：superseded 预警 {} 条（供应商 {} {}-Q{}）",
        event.taskSupplierId(),
        superseded,
        event.supplierId(),
        event.year(),
        event.quarter());
  }
}

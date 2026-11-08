package com.spe.context.procurement.api;

import java.time.LocalDate;

/**
 * 应付子账只读门面（W5）：财务对账读取发票/付款事实，不复制 P2P 表、也不依赖其 internal。
 */
public interface ProcurementApQueryFacade {

  /**
   * 截至 asOf 的已批准发票总额 - 已付款 = 应付余额（含部分付款）。
   *
   * <p>发票按 `invoice_date`、付款按 `paid_at` 截断；两者与 GL 的 `entry_date` 口径一致，
   * 否则“截至某日”会拿全量付除数去减历史 GL。
   */
  ApSubledgerSummary summarise(long companyId, LocalDate asOf);
}

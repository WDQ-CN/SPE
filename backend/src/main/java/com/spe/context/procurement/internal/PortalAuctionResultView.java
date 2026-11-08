package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 竞价关闭后的门户结果（匿名，不暴露对手身份）。 */
public record PortalAuctionResultView(
    AuctionStatus status,
    boolean sealed,
    LocalDateTime serverTime,
    List<Result> results) {

  public record Result(
      long lotId,
      Integer myRank,
      BigDecimal myBestAmount,
      BigDecimal winningAmount,
      boolean winner) {}
}

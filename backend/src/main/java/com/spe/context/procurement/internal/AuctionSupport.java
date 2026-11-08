package com.spe.context.procurement.internal;

import com.spe.common.security.Hashes;

/** 竞价辅助：请求哈希与规范化。 */
final class AuctionSupport {

  private AuctionSupport() {}

  static String requestHash(long auctionId, long lotId, String amount) {
    return Hashes.sha256Hex(auctionId + "|" + lotId + "|" + amount);
  }
}

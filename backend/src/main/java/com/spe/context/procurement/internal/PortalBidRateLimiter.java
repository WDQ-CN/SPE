package com.spe.context.procurement.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 门户出价频控（docs/erp/56 §11）：按「门户账号 + 竞价」固定窗口计数，进程内实现。
 *
 * <p>单机拓扑够用；多实例部署需改为 Redis/网关限流，登记为后续项。计数条目有上限，
 * 防止大量随机账号推高内存。
 */
@Component
public class PortalBidRateLimiter {

  private final int maxPerWindow;
  private final long windowMillis;
  private final int maxEntries;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public PortalBidRateLimiter(PortalBidLimiterProperties properties) {
    this.maxPerWindow = Math.max(1, properties.getMaxPerWindow());
    this.windowMillis = Math.max(1000, properties.getWindow().toMillis());
    this.maxEntries = Math.max(1000, properties.getMaxEntries());
  }

  public void check(long portalUserId, long auctionId) {
    long now = System.currentTimeMillis();
    if (windows.size() > maxEntries) {
      windows.entrySet().removeIf(entry -> entry.getValue().resetAt <= now);
    }
    String key = portalUserId + ":" + auctionId;
    Window window =
        windows.compute(
            key,
            (ignored, current) -> {
              if (current == null || current.resetAt <= now) {
                return new Window(now + windowMillis, 1);
              }
              current.count++;
              return current;
            });
    if (window.count > maxPerWindow) {
      throw new BizException(ErrorCode.PORTAL_RATE_LIMITED);
    }
  }

  private static final class Window {
    private final long resetAt;
    private int count;

    private Window(long resetAt, int count) {
      this.resetAt = resetAt;
      this.count = count;
    }
  }
}

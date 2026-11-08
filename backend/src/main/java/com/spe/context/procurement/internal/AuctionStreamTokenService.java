package com.spe.context.procurement.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * SSE 短令牌（docs/erp/56 §6）：EventSource 无法带 Authorization 头，先由已认证请求换取
 * 一次性短令牌，再用 `?token=` 订阅。令牌只放内存，不落库、不写审计。
 */
@Component
public class AuctionStreamTokenService {

  private static final int MAX_ENTRIES = 10000;

  private final ConcurrentHashMap<String, Entry> tokens = new ConcurrentHashMap<>();

  public Issued issue(long auctionId, long supplierId) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    if (tokens.size() >= MAX_ENTRIES) {
      tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
      if (tokens.size() >= MAX_ENTRIES) {
        // 未过期令牌占满上限时淘汰最早到期的一条，保证 map 有硬上限（每个令牌只需活 5 分钟）。
        tokens.entrySet().stream()
            .min(Comparator.comparing(entry -> entry.getValue().expiresAt))
            .ifPresent(entry -> tokens.remove(entry.getKey(), entry.getValue()));
      }
    }
    String token = UUID.randomUUID().toString().replace("-", "");
    LocalDateTime expiresAt = now.plusMinutes(5);
    tokens.put(token, new Entry(auctionId, supplierId, expiresAt));
    return new Issued(token, expiresAt);
  }

  public long consume(long auctionId, String token) {
    if (token == null || token.isBlank()) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_STREAM_TOKEN_INVALID);
    }
    Entry entry = tokens.remove(token);
    if (entry == null
        || entry.auctionId != auctionId
        || entry.expiresAt.isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_STREAM_TOKEN_INVALID);
    }
    return entry.supplierId;
  }

  public record Issued(String token, LocalDateTime expiresAt) {}

  private record Entry(long auctionId, long supplierId, LocalDateTime expiresAt) {}
}

package com.spe.context.integration.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 幂等键：先占位再执行，成功写回响应；同键不同内容拒绝，处理中防并发。 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

  private static final int TTL_HOURS = 24;

  private final IdempotencyKeyMapper mapper;

  @Transactional
  public IdempotencyRecord begin(String scope, String key, String requestHash) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    IdempotencyRecord existing = mapper.find(scope, key).orElse(null);
    if (existing != null && existing.getExpiresAt().isBefore(now)) {
      mapper.delete(scope, key);
      existing = null;
    }
    if (existing != null) {
      return resolve(existing, requestHash);
    }
    try {
      mapper.reserve(scope, key, requestHash, now.plusHours(TTL_HOURS));
      return null;
    } catch (DuplicateKeyException ex) {
      IdempotencyRecord concurrent = mapper.find(scope, key).orElseThrow(() -> ex);
      return resolve(concurrent, requestHash);
    }
  }

  @Transactional
  public void complete(String scope, String key, int status, String body) {
    mapper.complete(scope, key, status, body);
  }

  @Transactional
  public void abort(String scope, String key) {
    mapper.delete(scope, key);
  }

  private static IdempotencyRecord resolve(IdempotencyRecord record, String requestHash) {
    if (!record.getRequestHash().equals(requestHash)) {
      throw new BizException(ErrorCode.AUCTION_IDEMPOTENCY_CONFLICT);
    }
    if (record.getResponseStatus() == null || record.getResponseStatus() == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "同一幂等键的请求正在处理中");
    }
    return record;
  }
}

package com.spe.context.procurement.internal;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 竞价 SSE 连接注册表：按 (auctionId, supplierId) 分组，出价后主动推送各自的状态。 */
@Component
public class AuctionStreamService {

  private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

  private final ConcurrentHashMap<StreamKey, CopyOnWriteArrayList<SseEmitter>> emitters =
      new ConcurrentHashMap<>();

  public SseEmitter subscribe(long auctionId, long supplierId) {
    StreamKey key = new StreamKey(auctionId, supplierId);
    SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
    emitters.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> remove(key, emitter));
    emitter.onTimeout(() -> remove(key, emitter));
    emitter.onError(error -> remove(key, emitter));
    return emitter;
  }

  public List<Long> suppliers(long auctionId) {
    return emitters.keySet().stream()
        .filter(key -> key.auctionId == auctionId)
        .map(StreamKey::supplierId)
        .distinct()
        .toList();
  }

  public void send(long auctionId, long supplierId, Object state) {
    StreamKey key = new StreamKey(auctionId, supplierId);
    List<SseEmitter> list = emitters.get(key);
    if (list == null) {
      return;
    }
    for (SseEmitter emitter : list) {
      try {
        emitter.send(SseEmitter.event().name("state").data(state));
      } catch (Exception ex) {
        remove(key, emitter);
        emitter.completeWithError(ex);
      }
    }
  }

  private void remove(StreamKey key, SseEmitter emitter) {
    List<SseEmitter> list = emitters.get(key);
    if (list != null) {
      list.remove(emitter);
      if (list.isEmpty()) {
        emitters.remove(key, list);
      }
    }
  }

  private record StreamKey(long auctionId, long supplierId) {}
}

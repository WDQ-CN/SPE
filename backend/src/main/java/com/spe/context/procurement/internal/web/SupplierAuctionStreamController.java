package com.spe.context.procurement.internal.web;

import com.spe.context.procurement.internal.SupplierAuctionPortalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 竞价 SSE（docs/erp/56 §6）：公开路径，但必须携带一次性短令牌。 */
@RestController
@PreAuthorize("permitAll()")
@RequiredArgsConstructor
public class SupplierAuctionStreamController {

  private final SupplierAuctionPortalService portalService;

  @GetMapping(
      value = "/api/supplier-portal/auctions/{id}/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable long id, @RequestParam String token) {
    return portalService.stream(id, token);
  }
}

package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.SupplierPortalPrincipal;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.PortalAuctionResultView;
import com.spe.context.procurement.internal.PortalAuctionStateView;
import com.spe.context.procurement.internal.PortalAuctionSummaryView;
import com.spe.context.procurement.internal.PortalAuctionView;
import com.spe.context.procurement.internal.PortalStreamTokenView;
import com.spe.context.procurement.internal.PortalBidResult;
import com.spe.context.procurement.internal.PortalBidView;
import com.spe.context.procurement.internal.SupplierAuctionPortalService;
import com.spe.context.procurement.internal.web.dto.PortalBidRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 供应商门户竞价（独立门户令牌；docs/erp/56）。 */
@RestController
@RequestMapping("/api/supplier-portal/auctions")
@PreAuthorize("hasAuthority('PORTAL_SUPPLIER')")
@Validated
@RequiredArgsConstructor
public class SupplierAuctionPortalController {

  private final SupplierAuctionPortalService portalService;

  @GetMapping
  public Result<PageResult<PortalAuctionSummaryView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status,
      @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(portalService.page(principal.supplierId(), page, size, status));
  }

  @GetMapping("/{id}")
  public Result<PortalAuctionView> detail(
      @PathVariable long id, @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(portalService.detail(principal.supplierId(), id));
  }

  @GetMapping("/{id}/my-bids")
  public Result<List<PortalBidView>> myBids(
      @PathVariable long id, @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(portalService.myBids(principal.supplierId(), id));
  }

  @GetMapping("/{id}/state")
  public Result<PortalAuctionStateView> state(
      @PathVariable long id,
      @RequestParam(required = false) Long lotId,
      @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(portalService.state(principal.supplierId(), id, lotId));
  }

  @GetMapping("/{id}/result")
  public Result<PortalAuctionResultView> result(
      @PathVariable long id, @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(portalService.result(principal.supplierId(), id));
  }

  @PostMapping("/{id}/stream-token")
  public Result<PortalStreamTokenView> streamToken(
      @PathVariable long id, @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(portalService.streamToken(principal.supplierId(), id));
  }

  @PostMapping("/{id}/lots/{lotId}/bids")
  public Result<PortalBidResult> bid(
      @PathVariable long id,
      @PathVariable long lotId,
      @Valid @RequestBody PortalBidRequest request,
      @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(
        portalService.bid(
            principal.portalUserId(),
            principal.supplierId(),
            principal.loginName(),
            id,
            lotId,
            request.amount(),
            request.idempotencyKey()));
  }
}

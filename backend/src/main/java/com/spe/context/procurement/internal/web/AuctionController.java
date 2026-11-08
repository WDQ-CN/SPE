package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.AuctionDetailView;
import com.spe.context.procurement.internal.AuctionEvent;
import com.spe.context.procurement.internal.AuctionLotCommand;
import com.spe.context.procurement.internal.AuctionService;
import com.spe.context.procurement.internal.web.dto.AuctionLotRequest;
import com.spe.context.procurement.internal.web.dto.CreateAuctionRequest;
import com.spe.context.procurement.internal.web.dto.VersionedRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.spe.common.security.AuthenticatedOperator;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 内部竞价管理（E2 S4；不是门户端点）。 */
@RestController
@RequestMapping("/api/sourcing/auctions")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class AuctionController {

  private final AuctionService auctionService;

  @GetMapping
  public Result<PageResult<AuctionEvent>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(auctionService.page(page, size, companyId, status));
  }

  @PostMapping
  public Result<AuctionDetailView> create(
      @Valid @RequestBody CreateAuctionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        auctionService.create(
            operator.uid(),
            request.companyId(),
            request.title(),
            request.currency(),
            request.startAt(),
            request.endAt(),
            request.sealed(),
            request.antiSnipingSeconds(),
            request.maxExtensions(),
            request.lots().stream().map(AuctionController::command).toList(),
            request.supplierIds()));
  }

  @GetMapping("/{id}")
  public Result<AuctionDetailView> detail(@PathVariable long id) {
    return Result.ok(auctionService.detail(id));
  }

  @PostMapping("/{id}/start")
  public Result<AuctionDetailView> start(
      @PathVariable long id, @Valid @RequestBody VersionedRequest request) {
    return Result.ok(auctionService.start(id, request.version()));
  }

  @PostMapping("/{id}/close")
  public Result<AuctionDetailView> close(
      @PathVariable long id, @Valid @RequestBody VersionedRequest request) {
    return Result.ok(auctionService.close(id, request.version()));
  }

  private static AuctionLotCommand command(AuctionLotRequest request) {
    return new AuctionLotCommand(
        request.itemId(),
        request.qty(),
        request.uom(),
        request.startPrice(),
        request.reservePrice(),
        request.minDecrement());
  }
}

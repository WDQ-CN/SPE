package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.AuctionResultService;
import com.spe.context.procurement.internal.AuctionResultView;
import com.spe.context.procurement.internal.PurchaseOrderView;
import com.spe.context.procurement.internal.SourcingAwardService;
import com.spe.context.procurement.internal.SourcingAwardView;
import com.spe.context.procurement.internal.SourcingComparisonView;
import com.spe.context.procurement.internal.SourcingRfq;
import com.spe.context.procurement.internal.SourcingRfqService;
import com.spe.context.procurement.internal.SourcingRfqView;
import com.spe.context.procurement.internal.SupplierQuoteView;
import com.spe.context.procurement.internal.web.dto.AuctionToPoRequest;
import com.spe.context.procurement.internal.web.dto.CreateAwardRequest;
import com.spe.context.procurement.internal.web.dto.CreateRfqRequest;
import com.spe.context.procurement.internal.web.dto.InviteRfqRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** 寻源：RFQ/报价/比价/定标（docs/erp/55）。 */
@RestController
@RequestMapping("/api/sourcing")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SourcingController {

  private final SourcingRfqService rfqService;
  private final SourcingAwardService awardService;
  private final AuctionResultService auctionResultService;

  @GetMapping("/rfqs")
  public Result<PageResult<SourcingRfq>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status,
      @RequestParam(required = false) @Size(max = 100) String keyword) {
    return Result.ok(rfqService.page(page, size, companyId, status, keyword));
  }

  @PostMapping("/rfqs")
  public Result<SourcingRfqView> create(
      @Valid @RequestBody CreateRfqRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        rfqService.create(
            operator.uid(),
            request.companyId(),
            request.plantId(),
            request.title(),
            request.currency(),
            request.deadlineAt(),
            request.sealed(),
            request.sourcePrId(),
            request.awardPolicy(),
            request.lines().stream()
                .map(
                    line ->
                        new SourcingRfqService.RfqLineCommand(
                            line.itemId(),
                            line.qty(),
                            line.uom(),
                            line.needDate(),
                            line.targetPrice(),
                            line.spec()))
                .toList()));
  }

  @GetMapping("/rfqs/{id}")
  public Result<SourcingRfqView> detail(@PathVariable long id) {
    return Result.ok(rfqService.detail(id));
  }

  @PostMapping("/rfqs/{id}/invite")
  public Result<SourcingRfqView> invite(
      @PathVariable long id, @Valid @RequestBody InviteRfqRequest request) {
    return Result.ok(rfqService.invite(id, request.supplierIds()));
  }

  @PostMapping("/rfqs/{id}/send")
  public Result<SourcingRfqView> send(@PathVariable long id) {
    return Result.ok(rfqService.send(id));
  }

  @PostMapping("/rfqs/{id}/close")
  public Result<SourcingRfqView> close(@PathVariable long id) {
    return Result.ok(rfqService.close(id));
  }

  @PostMapping("/rfqs/{id}/cancel")
  public Result<SourcingRfqView> cancel(@PathVariable long id) {
    return Result.ok(rfqService.cancel(id));
  }

  @GetMapping("/rfqs/{id}/quotes")
  public Result<List<SupplierQuoteView>> quotes(@PathVariable long id) {
    return Result.ok(rfqService.quotes(id));
  }

  @GetMapping("/rfqs/{id}/comparison")
  public Result<SourcingComparisonView> comparison(@PathVariable long id) {
    return Result.ok(rfqService.comparison(id));
  }

  @GetMapping("/rfqs/{id}/awards")
  public Result<List<SourcingAwardView>> awards(@PathVariable long id) {
    return Result.ok(awardService.listByRfq(id));
  }

  @PostMapping("/rfqs/{id}/awards")
  public Result<SourcingAwardView> createAward(
      @PathVariable long id,
      @Valid @RequestBody CreateAwardRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        awardService.create(
            operator.uid(),
            id,
            request.supplierId(),
            request.reason(),
            request.lines().stream()
                .map(
                    line ->
                        new SourcingAwardService.AwardLineCommand(
                            line.rfqLineId(), line.quoteLineId(), line.awardedQty()))
                .toList()));
  }

  @GetMapping("/awards/{id}")
  public Result<SourcingAwardView> awardDetail(@PathVariable long id) {
    return Result.ok(awardService.detail(id));
  }

  @PostMapping("/awards/{id}/approve")
  public Result<SourcingAwardView> approve(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(awardService.approve(id, operator.uid()));
  }

  @PostMapping("/awards/{id}/to-po")
  public Result<PurchaseOrderView> toPurchaseOrder(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(awardService.toPurchaseOrder(id, operator.uid()));
  }

  @GetMapping("/auctions/{id}/results")
  public Result<List<AuctionResultView>> auctionResults(@PathVariable long id) {
    return Result.ok(auctionResultService.list(id));
  }

  @PostMapping("/auctions/{id}/award")
  public Result<List<AuctionResultView>> awardAuction(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(auctionResultService.award(id, operator.uid()));
  }

  @GetMapping("/auction-results/{id}")
  public Result<AuctionResultView> auctionResult(@PathVariable long id) {
    return Result.ok(auctionResultService.detail(id));
  }

  @PostMapping("/auction-results/{id}/approve")
  public Result<AuctionResultView> approveAuctionResult(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(auctionResultService.approve(id, operator.uid()));
  }

  @PostMapping("/auction-results/{id}/to-po")
  public Result<PurchaseOrderView> auctionResultToPo(
      @PathVariable long id,
      @Valid @RequestBody AuctionToPoRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        auctionResultService.toPurchaseOrder(id, operator.uid(), request.plantId()));
  }
}

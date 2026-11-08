package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.SupplierPortalPrincipal;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.PortalRfqSummaryView;
import com.spe.context.procurement.internal.PortalRfqView;
import com.spe.context.procurement.internal.SourcingRfqService;
import com.spe.context.procurement.internal.SupplierQuoteView;
import com.spe.context.procurement.internal.web.dto.PortalQuoteRequest;
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

/** 供应商门户 RFQ/报价（独立门户令牌；docs/erp/55 §3.7、56）。 */
@RestController
@RequestMapping("/api/supplier-portal/rfqs")
@PreAuthorize("hasAuthority('PORTAL_SUPPLIER')")
@Validated
@RequiredArgsConstructor
public class SupplierSourcingPortalController {

  private final SourcingRfqService rfqService;

  @GetMapping
  public Result<PageResult<PortalRfqSummaryView>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @Size(max = 16) String status,
      @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(rfqService.portalPage(principal.supplierId(), page, size, status));
  }

  @GetMapping("/{id}")
  public Result<PortalRfqView> detail(
      @PathVariable long id, @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(rfqService.portalDetail(principal.supplierId(), id));
  }

  @GetMapping("/{id}/quotes")
  public Result<List<SupplierQuoteView>> myQuotes(
      @PathVariable long id, @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(rfqService.portalQuotes(principal.supplierId(), id));
  }

  @PostMapping("/{id}/quotes")
  public Result<SupplierQuoteView> submitQuote(
      @PathVariable long id,
      @Valid @RequestBody PortalQuoteRequest request,
      @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(
        rfqService.submitQuote(
            principal.supplierId(),
            principal.portalUserId(),
            id,
            request.validUntil(),
            request.lines().stream()
                .map(
                    line ->
                        new SourcingRfqService.QuoteLineCommand(
                            line.rfqLineId(),
                            line.unitPrice(),
                            line.taxRate(),
                            line.leadTimeDays(),
                            line.moq(),
                            line.qtyAvailable(),
                            line.incoterm(),
                            line.remark()))
                .toList()));
  }

  @PostMapping("/{id}/withdraw")
  public Result<SupplierQuoteView> withdraw(
      @PathVariable long id, @AuthenticationPrincipal SupplierPortalPrincipal principal) {
    return Result.ok(rfqService.withdrawQuote(principal.supplierId(), id));
  }
}

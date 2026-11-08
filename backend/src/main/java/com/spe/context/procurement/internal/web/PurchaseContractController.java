package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.ContractConsumptionView;
import com.spe.context.procurement.internal.PurchaseContract;
import com.spe.context.procurement.internal.PurchaseContractService;
import com.spe.context.procurement.internal.PurchaseContractView;
import com.spe.context.procurement.internal.PurchaseOrderView;
import com.spe.context.procurement.internal.SupplierPriceView;
import com.spe.context.procurement.internal.web.dto.CreateContractRequest;
import com.spe.context.procurement.internal.web.dto.CreateSupplierPriceRequest;
import com.spe.context.procurement.internal.web.dto.ReleaseContractOrderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

/** 合同/框架协议与供应商价格库（docs/erp/55 §3.5）。 */
@RestController
@RequestMapping("/api/procurement")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class PurchaseContractController {

  private final PurchaseContractService contractService;

  @GetMapping("/contracts")
  public Result<PageResult<PurchaseContract>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long supplierId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(contractService.page(page, size, companyId, supplierId, status));
  }

  @PostMapping("/contracts")
  public Result<PurchaseContractView> create(
      @Valid @RequestBody CreateContractRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        contractService.create(
            operator.uid(),
            request.companyId(),
            request.supplierId(),
            request.title(),
            request.currency(),
            request.validFrom(),
            request.validTo(),
            request.paymentTerms(),
            request.totalLimitAmount(),
            request.totalLimitQty(),
            request.lines().stream()
                .map(
                    line ->
                        new PurchaseContractService.ContractLineCommand(
                            line.itemId(),
                            line.uom(),
                            line.price(),
                            line.minQty(),
                            line.maxQty(),
                            line.taxIncluded(),
                            line.validFrom(),
                            line.validTo()))
                .toList()));
  }

  @GetMapping("/contracts/{id}")
  public Result<PurchaseContractView> detail(@PathVariable long id) {
    return Result.ok(contractService.detail(id));
  }

  @PostMapping("/contracts/{id}/activate")
  public Result<PurchaseContractView> activate(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(contractService.activate(id, operator.uid()));
  }

  @PostMapping("/contracts/{id}/terminate")
  public Result<PurchaseContractView> terminate(@PathVariable long id) {
    return Result.ok(contractService.terminate(id));
  }

  @PostMapping("/contracts/{id}/release-order")
  public Result<PurchaseOrderView> releaseOrder(
      @PathVariable long id,
      @Valid @RequestBody ReleaseContractOrderRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        contractService.releaseOrder(
            id,
            operator.uid(),
            request.plantId(),
            request.lines().stream()
                .map(
                    line ->
                        new PurchaseContractService.ReleaseLineCommand(
                            line.contractLineId(), line.qty()))
                .toList()));
  }

  @GetMapping("/contracts/{id}/consumption")
  public Result<List<ContractConsumptionView>> consumption(@PathVariable long id) {
    return Result.ok(contractService.consumption(id));
  }

  @GetMapping("/supplier-prices")
  public Result<List<SupplierPriceView>> effectivePrices(
      @RequestParam long supplierId,
      @RequestParam(required = false) Long itemId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return Result.ok(contractService.effectivePrices(supplierId, itemId, date));
  }

  @PostMapping("/supplier-prices")
  public Result<SupplierPriceView> createSupplierPrice(
      @Valid @RequestBody CreateSupplierPriceRequest request) {
    return Result.ok(
        contractService.createSupplierPrice(
            request.companyId(),
            request.supplierId(),
            request.itemId(),
            request.uom(),
            request.minQty(),
            request.price(),
            request.taxIncluded(),
            request.currency(),
            request.validFrom(),
            request.validTo()));
  }
}

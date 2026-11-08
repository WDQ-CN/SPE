package com.spe.context.quality.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.quality.internal.Capa;
import com.spe.context.quality.internal.CapaView;
import com.spe.context.quality.internal.Ncr;
import com.spe.context.quality.internal.NcrCapaService;
import com.spe.context.quality.internal.NcrView;
import com.spe.context.quality.internal.web.dto.QualityRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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

/** NCR 与 CAPA（docs/erp/37）。 */
@RestController
@RequestMapping("/api/quality")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class QualityNcrController {

  private final NcrCapaService ncrCapaService;

  @GetMapping("/ncrs")
  public Result<PageResult<Ncr>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(ncrCapaService.pageNcr(page, size, companyId, status));
  }

  @PostMapping("/ncrs")
  public Result<NcrView> create(
      @Valid @RequestBody QualityRequests.CreateNcr request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        ncrCapaService.create(
            operator.uid(),
            request.companyId(),
            request.itemId(),
            request.supplierId(),
            request.lotNo(),
            request.qty(),
            request.defect()));
  }

  @GetMapping("/ncrs/{id}")
  public Result<NcrView> detail(@PathVariable long id) {
    return Result.ok(ncrCapaService.ncrDetail(id));
  }

  @PostMapping("/ncrs/{id}/disposition")
  public Result<NcrView> dispose(
      @PathVariable long id, @Valid @RequestBody QualityRequests.DisposeNcr request) {
    return Result.ok(ncrCapaService.dispose(id, request.disposition(), request.reason()));
  }

  @PostMapping("/ncrs/{id}/close")
  public Result<NcrView> close(@PathVariable long id) {
    return Result.ok(ncrCapaService.close(id));
  }

  @GetMapping("/capas")
  public Result<PageResult<Capa>> listCapa(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(ncrCapaService.pageCapa(page, size, companyId, status));
  }

  @PostMapping("/capas")
  public Result<CapaView> createCapa(
      @Valid @RequestBody QualityRequests.CreateCapa request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        ncrCapaService.createCapa(
            operator.uid(), request.ncrId(), request.rootCause(), request.actionPlan()));
  }

  @GetMapping("/capas/{id}")
  public Result<CapaView> capaDetail(@PathVariable long id) {
    return Result.ok(ncrCapaService.capaDetail(id));
  }

  @PostMapping("/capas/{id}/verify")
  public Result<CapaView> verifyCapa(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(ncrCapaService.verifyCapa(id, operator.uid()));
  }

  @PostMapping("/capas/{id}/close")
  public Result<CapaView> closeCapa(@PathVariable long id) {
    return Result.ok(ncrCapaService.closeCapa(id));
  }
}

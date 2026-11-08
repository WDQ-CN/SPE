package com.spe.context.quality.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.quality.internal.SupplierCertificateService;
import com.spe.context.quality.internal.SupplierCertificateView;
import com.spe.context.quality.internal.web.dto.QualityRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 供应商证书（docs/erp/37）。 */
@RestController
@RequestMapping("/api/quality/certificates")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SupplierCertificateController {

  private final SupplierCertificateService certificateService;

  @GetMapping
  public Result<PageResult<SupplierCertificateView>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long supplierId) {
    return Result.ok(certificateService.page(page, size, companyId, supplierId));
  }

  @GetMapping("/expiring")
  public Result<List<SupplierCertificateView>> expiring(
      @RequestParam long companyId, @RequestParam(defaultValue = "30") int days) {
    return Result.ok(certificateService.expiring(companyId, days));
  }

  @PostMapping
  public Result<SupplierCertificateView> create(
      @Valid @RequestBody QualityRequests.CreateCertificate request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        certificateService.create(
            operator.uid(),
            request.companyId(),
            request.supplierId(),
            request.itemId(),
            request.certType(),
            request.certNo(),
            request.validFrom(),
            request.validTo()));
  }
}

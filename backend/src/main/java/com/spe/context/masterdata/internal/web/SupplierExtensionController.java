package com.spe.context.masterdata.internal.web;

import com.spe.common.web.Result;
import com.spe.context.masterdata.internal.SupplierExtensionService;
import com.spe.context.masterdata.internal.SupplierExtensionView;
import com.spe.context.masterdata.internal.web.dto.UpsertSupplierExtensionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 供应商扩展接口（1:1）。 */
@RestController
@RequestMapping("/api/masterdata/suppliers/{supplierId}/extension")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class SupplierExtensionController {

  private final SupplierExtensionService extensionService;

  @GetMapping
  public Result<SupplierExtensionView> get(@PathVariable long supplierId) {
    return Result.ok(extensionService.get(supplierId).orElse(null));
  }

  @PutMapping
  public Result<SupplierExtensionView> upsert(
      @PathVariable long supplierId, @Valid @RequestBody UpsertSupplierExtensionRequest request) {
    return Result.ok(
        extensionService.upsert(
            supplierId,
            request.taxNo(),
            request.bankAccount(),
            request.bankName(),
            request.paymentTerms(),
            request.currency(),
            request.incoterms(),
            request.qualificationStatus(),
            request.certificateExpiry(),
            request.version()));
  }
}

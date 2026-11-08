package com.spe.context.supplier.internal.web;

import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.supplier.internal.SupplierPortalInvitationService;
import com.spe.context.supplier.internal.web.dto.AcceptPortalInvitationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 内部创建门户邀请 Token（docs/erp/56 §2/§5）。 */
@RestController
@Validated
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class SupplierPortalInvitationController {

  private final SupplierPortalInvitationService invitationService;

  @PostMapping("/api/suppliers/{supplierId}/portal-invitation")
  public Result<SupplierPortalInvitationService.InvitationCreated> create(
      @PathVariable long supplierId,
      @RequestParam(required = false) @Min(1) @Max(720) Integer ttlHours,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(invitationService.createInvitation(supplierId, operator.uid(), ttlHours));
  }
}

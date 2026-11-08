package com.spe.context.supplier.internal.web;

import com.spe.common.web.Result;
import com.spe.context.supplier.internal.SupplierPortalInvitationService;
import com.spe.context.supplier.internal.web.dto.AcceptPortalInvitationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 公开的邀请接受端点：Token 本身即凭证（docs/erp/56 §2）。 */
@RestController
@PreAuthorize("permitAll()")
@RequiredArgsConstructor
public class SupplierPortalInvitationAcceptController {

  private final SupplierPortalInvitationService invitationService;

  @PostMapping("/api/supplier-portal/invitations/{token}/accept")
  public Result<SupplierPortalInvitationService.InvitationAccepted> accept(
      @PathVariable String token, @Valid @RequestBody AcceptPortalInvitationRequest request) {
    return Result.ok(
        invitationService.accept(token, request.loginName(), request.password()));
  }
}

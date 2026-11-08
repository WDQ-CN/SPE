package com.spe.context.supplier.internal.web;

import com.spe.common.web.Result;
import com.spe.context.supplier.internal.SupplierPortalAccountService;
import com.spe.context.supplier.internal.web.dto.CreateSupplierPortalAccountRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 创建供应商门户账号（内部管理员；不是门户端点）。 */
@RestController
@RequestMapping("/api/suppliers")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class SupplierPortalAccountController {

  private final SupplierPortalAccountService accountService;

  @PostMapping("/{supplierId}/portal-user")
  public Result<Long> create(
      @PathVariable long supplierId, @Valid @RequestBody CreateSupplierPortalAccountRequest request) {
    return Result.ok(accountService.create(supplierId, request.loginName(), request.password()));
  }
}

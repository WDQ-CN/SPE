package com.spe.context.system.internal.web;

import com.spe.common.web.Result;
import com.spe.context.system.internal.portal.SupplierPortalAuthService;
import com.spe.context.system.internal.portal.SupplierPortalUserService;
import com.spe.context.system.internal.portal.SupplierPortalUserView;
import com.spe.context.system.internal.web.dto.SupplierPortalLoginRequest;
import com.spe.context.system.internal.web.dto.SupplierPortalRefreshRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商门户认证（独立于内部 AuthController）。
 *
 * <p>登录/刷新免认证；登出要求已认证的门户令牌。URL 规则与内部 JWT 过滤器互不通用。
 */
@RestController
@RequestMapping("/api/supplier-portal/auth")
@PreAuthorize("permitAll()")
@RequiredArgsConstructor
public class SupplierPortalAuthController {

  private final SupplierPortalAuthService authService;
  private final SupplierPortalUserService userService;

  @PostMapping("/login")
  public Result<PortalLoginResponse> login(
      @Valid @RequestBody SupplierPortalLoginRequest request) {
    SupplierPortalAuthService.PortalLoginResult result =
        authService.login(request.loginName(), request.password());
    return Result.ok(PortalLoginResponse.from(result));
  }

  @PostMapping("/refresh")
  public Result<PortalLoginResponse> refresh(
      @Valid @RequestBody SupplierPortalRefreshRequest request) {
    return Result.ok(PortalLoginResponse.from(authService.refresh(request.refreshToken())));
  }

  @PostMapping("/logout")
  @PreAuthorize("hasAuthority('PORTAL_SUPPLIER')")
  public Result<Void> logout(@Valid @RequestBody SupplierPortalRefreshRequest request) {
    authService.logout(request.refreshToken());
    return Result.ok();
  }

  public record PortalLoginResponse(
      String accessToken,
      String refreshToken,
      long expiresIn,
      SupplierPortalUserView user) {

    static PortalLoginResponse from(SupplierPortalAuthService.PortalLoginResult result) {
      return new PortalLoginResponse(
          result.accessToken(), result.refreshToken(), result.expiresIn(), result.user());
    }
  }
}

package com.spe.context.system.internal.web;

import com.spe.common.web.Result;
import com.spe.context.system.internal.security.AuthService;
import com.spe.context.system.internal.web.dto.ChangePasswordRequest;
import com.spe.context.system.internal.web.dto.LoginRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（02 §4）：`POST /api/auth/login`（免认证）、`PUT /api/auth/password`（需认证）。
 *
 * <p>登录端点必须在安全链里 permitAll，否则「未认证 → 401」会先于本类生效，永远登不进来
 * （见 {@code SecurityConfig}）。本类只做参数校验与转发：口令校验、状态判断、签发令牌都在
 * {@link AuthService}。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  @PostMapping("/login")
  public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    AuthService.LoginResult result = authService.login(request.username(), request.password());
    return Result.ok(
        new LoginResponse(result.token(), result.name(), result.roles(), result.department()));
  }

  /**
   * 01 A3：本人改口令。操作人取自认证主体（`JwtAuthenticationFilter` 写入的账号），
   * 请求体里没有账号字段——改谁的口令不能由客户端指定。
   */
  @PutMapping("/password")
  public Result<Void> changePassword(
      @Valid @RequestBody ChangePasswordRequest request, Principal principal) {
    authService.changeOwnPassword(principal.getName(), request.oldPassword(), request.newPassword());
    return Result.ok();
  }

  /**
   * 登录响应（10 §E2：字段与前端 `AuthProfile` 对齐——`token`/`name`/`roles`/`department`）。
   *
   * <p>不返回 uid 等额外信息：前端只按角色渲染菜单，鉴权一律以后端为准（01 §7）。
   */
  public record LoginResponse(String token, String name, Set<String> roles, String department) {}
}

package com.spe.context.system.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.system.internal.user.UserService;
import com.spe.context.system.internal.user.UserSummary;
import com.spe.context.system.internal.web.dto.ChangeStatusRequest;
import com.spe.context.system.internal.web.dto.CreateUserRequest;
import com.spe.context.system.internal.web.dto.ResetPasswordRequest;
import com.spe.context.system.internal.web.dto.UpdateUserRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（02 §4 / 01 SM1、A4 的启停部分）。
 *
 * <p>鉴权锚定 01 §7：用户管理**仅 ADMIN**。类级 `@PreAuthorize` 是「默认拒绝」的落地——
 * 不必逐个方法重复注解，新增方法自动落在同一权限边界内（06 §2.7、05 §9）。
 * 越权由后端 4xxx 兜底并在 system 上下文留 `ACCESS_DENIED` 审计，不依赖前端隐藏入口。
 *
 * <p>A4 的「按角色/部门/状态筛选」依 10 §3 的说明后置，本阶段列表只有分页。
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;

  /** `page` 从 1 开始（09 D8），越界由 Service 收敛到合法范围而不是报错。 */
  @GetMapping
  public Result<PageResult<UserSummary>> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
    return Result.ok(userService.page(page, size));
  }

  @PostMapping
  public Result<UserSummary> create(@Valid @RequestBody CreateUserRequest request) {
    UserSummary created =
        userService.create(
            request.username(), request.password(), request.name(), request.role(), request.department());
    return Result.ok(created);
  }

  @PutMapping("/{id}")
  public Result<UserSummary> update(
      @PathVariable long id, @Valid @RequestBody UpdateUserRequest request) {
    UserSummary updated =
        userService.update(id, request.name(), request.role(), request.department(), request.version());
    return Result.ok(updated);
  }

  /** 启停（01 A4/SM1）：守卫 UPDATE，版本不符或已处于目标状态 → 3001；P2-8 自锁守卫见 `UserService`。 */
  @PutMapping("/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id,
      @Valid @RequestBody ChangeStatusRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    userService.changeStatus(id, request.status(), request.version(), operator.uid());
    return Result.ok();
  }

  /** ADMIN 重置他人口令（01 SM1）。 */
  @PutMapping("/{id}/password")
  public Result<Void> resetPassword(
      @PathVariable long id, @Valid @RequestBody ResetPasswordRequest request) {
    userService.resetPassword(id, request.newPassword());
    return Result.ok();
  }
}

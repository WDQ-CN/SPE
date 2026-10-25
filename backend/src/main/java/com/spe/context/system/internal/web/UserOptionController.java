package com.spe.context.system.internal.web;

import com.spe.common.web.Result;
import com.spe.context.system.internal.user.UserOption;
import com.spe.context.system.internal.user.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可选人员（02 §4 / 03 §38 的 P0-1）。
 *
 * <p><b>为什么要有它</b>：建任务与改派是 MANAGER 的权限（01 §7），而它们的选择器原先只能调
 * {@code /api/users}——那个控制器是类级 ADMIN-only，于是 MANAGER 一点「新建任务」就整段被 403 打断，
 * 三个下拉全空。放开 `/api/users` 会把管理面（含启停要用的 {@code version}）一并交出去，
 * 所以这里单开一个只读的窄投影。
 *
 * <p><b>权限边界（用户裁决，`18 §6` 点 2）</b>：类级 `hasAnyRole('ADMIN','MANAGER')`——消费方就是
 * 采购经理的两个选择器，不对 VIEWER/EVALUATOR 开放目录面。
 *
 * <p>类级注解同时满足 ArchUnit R-H「端点默认拒绝」（规则要的是**类上**的注解）。
 */
@RestController
@RequestMapping("/api/user-options")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class UserOptionController {

  private final UserService userService;

  /**
   * @param roles 角色白名单（多值，逗号分隔）；留空 = 不过滤角色（目录面的通用能力）。
   *              审批链选择器固定传 {@code MANAGER,ADMIN}（01 §3 经 2026-10-25 裁决收窄，
   *              {@code 21 §3.W1-1}），改派按 {@code TaskService.requireEligibleAssignee}
   *              的合法集合传 {@code EVALUATOR,MANAGER}——**集合由调用点决定，不在这里写死**。
   */
  @GetMapping
  public Result<List<UserOption>> list(
      @RequestParam(name = "roles", required = false) List<String> roles) {
    return Result.ok(userService.options(roles));
  }
}

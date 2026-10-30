package com.spe.context.evaluation.internal.web;

import com.spe.common.web.Result;
import com.spe.context.evaluation.internal.unlock.UnlockService;
import com.spe.context.evaluation.internal.web.dto.UnlockRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员解锁重评（01 R5；M5 W3，22 §2 D5）。
 *
 * <p>独立于审批 Controller：R5 明确「由管理员解锁」，这里类级就只给 ADMIN，不与 MANAGER 的审批入口混在
 * 同一个类上（两层权限的入口层不做"先放进来再拦"的写法）。
 */
@RestController
@RequestMapping("/api/task-suppliers")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class TaskSupplierUnlockController {

  private final UnlockService unlockService;

  @PostMapping("/{id}/unlock")
  public Result<Void> unlock(
      @PathVariable long id, @Valid @RequestBody UnlockRequest request) {
    unlockService.unlock(id, request.version(), request.comment());
    return Result.ok();
  }
}

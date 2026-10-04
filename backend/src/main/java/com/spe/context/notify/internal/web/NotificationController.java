package com.spe.context.notify.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.notify.internal.NotificationService;
import com.spe.context.notify.internal.NotificationSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知（01 SM6 最小版 / 13 §E16、§E18）：列表、未读数、全部已读。
 *
 * <p>**全角色可访问，但只看自己的**：`receiverId` 一律取自令牌里的 uid，SQL 里再钉一次
 * （第二层过滤，05 §9——不依赖前端）。
 */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationService notificationService;

  @GetMapping
  public Result<PageResult<NotificationSummary>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(notificationService.page(operator.uid(), PageQuery.of(page, size)));
  }

  @GetMapping("/unread-count")
  public Result<Long> unreadCount(@AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(notificationService.unreadCount(operator.uid()));
  }

  @PostMapping("/read-all")
  public Result<Integer> readAll(@AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(notificationService.markAllRead(operator.uid()));
  }
}

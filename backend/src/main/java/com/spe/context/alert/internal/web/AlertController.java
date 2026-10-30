package com.spe.context.alert.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.alert.internal.AlertService;
import com.spe.context.alert.internal.AlertView;
import com.spe.context.alert.internal.web.dto.HandleAlertRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预警列表与处理（01 W3；M5 W2/W3，02 §4）。
 *
 * <p>权限按 01 §7：列表 ADMIN/MANAGER/VIEWER；处理 ADMIN/MANAGER（方法级注解在类级之上再收一层）。
 */
@RestController
@RequestMapping("/api/alerts")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','VIEWER')")
@RequiredArgsConstructor
public class AlertController {

  private final AlertService alertService;

  @GetMapping
  public Result<PageResult<AlertView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String status) {
    return Result.ok(alertService.page(PageQuery.of(page, size), status));
  }

  @PutMapping("/{id}/handle")
  @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
  public Result<AlertView> handle(
      @PathVariable long id,
      @Valid @RequestBody(required = false) HandleAlertRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    String comment = request == null ? null : request.comment();
    return Result.ok(alertService.handle(id, operator.uid(), comment));
  }
}

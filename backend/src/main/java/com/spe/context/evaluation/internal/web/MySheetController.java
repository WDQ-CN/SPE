package com.spe.context.evaluation.internal.web;

import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.evaluation.internal.sheet.MySheetView;
import com.spe.context.evaluation.internal.task.TaskService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的待办（01 E1 / 13 §E13）：只列**指派给我**的评分单，按截止日期排序。
 *
 * <p>过滤写在 Service 内（`WHERE assignee_id = 我`），不靠前端隐藏入口；这也是 01 §7 数据可见性的
 * 第一条落地。
 */
@RestController
@RequestMapping("/api/my/sheets")
@PreAuthorize("hasAnyRole('MANAGER','EVALUATOR')")
@RequiredArgsConstructor
public class MySheetController {

  private final TaskService taskService;

  @GetMapping
  public Result<List<MySheetView>> mySheets(@AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(taskService.findMySheets(operator.uid()));
  }
}

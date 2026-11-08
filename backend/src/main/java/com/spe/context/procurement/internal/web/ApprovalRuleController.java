package com.spe.context.procurement.internal.web;

import com.spe.common.web.Result;
import com.spe.context.procurement.internal.ApprovalMatrixService;
import com.spe.context.procurement.internal.ApprovalRuleView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审批矩阵只读接口（docs/erp/50 §2）：规则随迁移种子，变更走后续配置工作包并审计。 */
@RestController
@RequestMapping("/api/procurement/approval-rules")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class ApprovalRuleController {

  private final ApprovalMatrixService approvalMatrixService;

  @GetMapping
  public Result<List<ApprovalRuleView>> list(@RequestParam(required = false) String docType) {
    return Result.ok(approvalMatrixService.list(docType));
  }
}

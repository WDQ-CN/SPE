package com.spe.context.workflow.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.Role;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 工作流单号与角色校验。 */
final class WorkflowSupport {

  private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private WorkflowSupport() {}

  static String instanceNo() {
    String random =
        UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    return "WF-" + LocalDate.now().format(DOC_DATE) + "-" + random;
  }

  static String role(String rawRole) {
    if (rawRole == null || rawRole.isBlank()) {
      throw new BizException(ErrorCode.WORKFLOW_APPROVER_ROLE_INVALID, "审批角色不能为空");
    }
    try {
      return Role.valueOf(rawRole.trim().toUpperCase()).name();
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.WORKFLOW_APPROVER_ROLE_INVALID, "审批角色不合法：" + rawRole);
    }
  }
}

package com.spe.context.workflow.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 工作流请求体集合。 */
public final class WorkflowRequests {

  private WorkflowRequests() {}

  public record CreateDefinition(
      @NotBlank(message = "编码不能为空") @Size(max = 64) String code,
      @NotBlank(message = "名称不能为空") @Size(max = 200) String name,
      @NotBlank(message = "单据类型不能为空") @Size(max = 64) String docType,
      @Size(max = 16) String versionNo,
      @DecimalMin(value = "0.0", message = "金额下限不能为负") BigDecimal conditionMinAmount,
      @DecimalMin(value = "0.0", message = "金额上限不能为负") BigDecimal conditionMaxAmount,
      @NotEmpty(message = "至少需要一个审批节点") @Valid List<Node> nodes) {

    public record Node(
        @NotBlank(message = "节点名称不能为空") @Size(max = 100) String name,
        @NotBlank(message = "审批角色不能为空") @Size(max = 32) String approverRole) {}
  }

  public record StartInstance(
      @NotNull(message = "公司不能为空") Long companyId,
      @NotBlank(message = "单据类型不能为空") @Size(max = 64) String docType,
      @NotNull(message = "单据 ID 不能为空") Long docId,
      @NotNull(message = "金额不能为空")
          @DecimalMin(value = "0.0", message = "金额不能为负")
          BigDecimal amount) {}

  public record Act(@Size(max = 500, message = "意见长度不得超过 500") String opinion) {}
}

package com.spe.context.project.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 项目预算版本。 */
@Data
@TableName("project_budget")
public class ProjectBudget {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long projectId;
  private String versionNo;
  private BigDecimal laborAmount;
  private BigDecimal purchaseAmount;
  private BigDecimal expenseAmount;
  private BigDecimal otherAmount;
  private BigDecimal totalAmount;
  private ProjectBudgetStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long approvedBy;
  private LocalDateTime approvedAt;
}

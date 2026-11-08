package com.spe.context.project.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 项目（V1029）。 */
@Data
@TableName("project")
public class Project {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String projectNo;
  private Long companyId;
  private Long customerId;
  private String name;
  private String currency;
  private BigDecimal budgetAmount;
  private BigDecimal defaultLaborRate;
  private ProjectStatus status;
  private LocalDate startDate;
  private LocalDate endDate;
  private Long managerId;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private LocalDateTime closedAt;
  private Integer version;
}

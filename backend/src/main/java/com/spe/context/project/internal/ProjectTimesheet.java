package com.spe.context.project.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 项目工时单。 */
@Data
@TableName("project_timesheet")
public class ProjectTimesheet {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String timesheetNo;
  private Long companyId;
  private Long userId;
  private TimesheetStatus status;
  private BigDecimal totalHours;
  private BigDecimal totalAmount;
  private LocalDateTime createdAt;
  private LocalDateTime submittedAt;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private Integer version;
}

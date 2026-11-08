package com.spe.context.project.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 工时行。 */
@Data
@TableName("project_timesheet_line")
public class TimesheetLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long timesheetId;
  private Integer lineNo;
  private Long projectId;
  private Long wbsId;
  private LocalDate workDate;
  private BigDecimal hours;
  private BigDecimal rate;
  private BigDecimal amount;
}

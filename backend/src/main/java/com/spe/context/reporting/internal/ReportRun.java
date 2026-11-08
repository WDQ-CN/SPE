package com.spe.context.reporting.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 报表运行快照。 */
@Data
@TableName("report_run")
public class ReportRun {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long definitionId;
  private Long companyId;
  private LocalDate asOf;
  private String status;
  private Integer rowCount;
  private String resultJson;
  private String errorMessage;
  private Long durationMs;
  private Long requestedBy;
  private LocalDateTime createdAt;
}

package com.spe.context.reporting.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 报表定义（V1034）。 */
@Data
@TableName("report_definition")
public class ReportDefinition {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String code;
  private String name;
  private String queryKey;
  private String description;
  private String status;
  private LocalDateTime createdAt;
}

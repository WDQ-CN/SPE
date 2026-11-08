package com.spe.context.project.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 项目成本。 */
@Data
@TableName("project_cost")
public class ProjectCost {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long projectId;
  private String sourceType;
  private Long sourceId;
  private Long sourceLineId;
  private BigDecimal amount;
  private String currency;
  private LocalDateTime postedAt;
}

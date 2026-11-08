package com.spe.context.project.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 项目 WBS。 */
@Data
@TableName("project_wbs")
public class ProjectWbs {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long projectId;
  private Long parentId;
  private String code;
  private String name;
  private BigDecimal budget;
  private WbsStatus status;
  private LocalDateTime createdAt;
}

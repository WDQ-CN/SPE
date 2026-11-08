package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 检验计划（V1027）。 */
@Data
@TableName("inspection_plan")
public class InspectionPlan {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String planNo;
  private Long companyId;
  private Long itemId;
  private Long supplierId;
  private InspectionType inspectionType;
  private String version;
  private InspectionPlanStatus status;
  private String samplingPlan;
  private java.math.BigDecimal aql;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime activatedAt;
}

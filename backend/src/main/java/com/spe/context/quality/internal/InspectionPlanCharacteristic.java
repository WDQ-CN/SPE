package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 检验特性。 */
@Data
@TableName("inspection_plan_characteristic")
public class InspectionPlanCharacteristic {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long planId;
  private Integer lineNo;
  private String name;
  private String spec;
  private Boolean critical;
  private String method;
}

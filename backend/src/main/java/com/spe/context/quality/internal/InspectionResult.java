package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 检验结果。 */
@Data
@TableName("inspection_result")
public class InspectionResult {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long inspectionId;
  private Integer lineNo;
  private Long characteristicId;
  private String name;
  private String measuredValue;
  private InspectionResultStatus result;
  private String remark;
}

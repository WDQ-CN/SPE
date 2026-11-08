package com.spe.context.governance.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** SoD 冲突规则（V1032）。 */
@Data
@TableName("sod_rule")
public class SodRule {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String code;
  private String roleA;
  private String roleB;
  private String description;
  private SodRuleStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
}

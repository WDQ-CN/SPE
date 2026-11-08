package com.spe.context.governance.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** SoD 例外（限时、审批、审计）。 */
@Data
@TableName("sod_exception")
public class SodException {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long userId;
  private Long ruleId;
  private String reason;
  private LocalDate validFrom;
  private LocalDate validTo;
  private SodExceptionStatus status;
  private Long requestedBy;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private LocalDateTime createdAt;
  private Integer version;
}

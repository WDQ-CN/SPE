package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 纠正预防措施（CAPA）。 */
@Data
@TableName("capa")
public class Capa {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String capaNo;
  private Long companyId;
  private Long ncrId;
  private Long supplierId;
  private String rootCause;
  private String actionPlan;
  private CapaStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long verifiedBy;
  private LocalDateTime verifiedAt;
  private LocalDateTime closedAt;
}

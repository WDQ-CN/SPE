package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 询价单（V1017；docs/erp/55 §3.1）。 */
@Data
@TableName("sourcing_rfq")
public class SourcingRfq {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String rfqNo;
  private Long companyId;
  private Long plantId;
  private Long buyerId;
  private String title;
  private String currency;
  private LocalDateTime deadlineAt;
  private Boolean sealed;
  private SourcingRfqStatus status;
  private Long sourcePrId;
  private AwardPolicy awardPolicy;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime sentAt;
  private LocalDateTime closedAt;
}

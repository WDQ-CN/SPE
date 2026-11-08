package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 检验单。 */
@Data
@TableName("inspection_order")
public class InspectionOrder {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String orderNo;
  private Long companyId;
  private Long planId;
  private String sourceType;
  private Long sourceId;
  private Long sourceLineId;
  private Long itemId;
  private Long supplierId;
  private String lotNo;
  private BigDecimal qty;
  private InspectionStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime completedAt;
}

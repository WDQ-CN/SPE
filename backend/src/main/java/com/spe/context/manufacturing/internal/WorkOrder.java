package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 生产工单（V1026）。 */
@Data
@TableName("work_order")
public class WorkOrder {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String orderNo;
  private Long companyId;
  private Long plantId;
  private Long itemId;
  private Long bomId;
  private BigDecimal qty;
  private WorkOrderStatus status;
  private BigDecimal materialCost;
  private BigDecimal receivedQty;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime releasedAt;
  private LocalDateTime completedAt;
}

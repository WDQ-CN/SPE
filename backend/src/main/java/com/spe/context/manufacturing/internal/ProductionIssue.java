package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 生产领料。 */
@Data
@TableName("production_issue")
public class ProductionIssue {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long workOrderId;
  private Long componentId;
  private Long itemId;
  private String lotNo;
  private Long locationId;
  private BigDecimal qty;
  private BigDecimal unitCost;
  private BigDecimal amount;
  private Long createdBy;
  private LocalDateTime createdAt;
}

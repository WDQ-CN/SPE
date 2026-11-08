package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 生产完工入库。 */
@Data
@TableName("production_receipt")
public class ProductionReceipt {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String receiptNo;
  private Long workOrderId;
  private Long warehouseId;
  private Long locationId;
  private String lotNo;
  private BigDecimal qty;
  private BigDecimal unitCost;
  private BigDecimal amount;
  private ProductionReceiptStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
}

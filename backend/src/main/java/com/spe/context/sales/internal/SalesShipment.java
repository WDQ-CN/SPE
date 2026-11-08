package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 销售发货单（V1022）。 */
@Data
@TableName("sales_shipment")
public class SalesShipment {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String shipmentNo;
  private Long companyId;
  private Long customerId;
  private Long orderId;
  private Long warehouseId;
  private String currency;
  private SalesShipmentStatus status;
  private LocalDateTime shippedAt;
  private Long createdBy;
  private Integer version;
  private LocalDateTime createdAt;
}

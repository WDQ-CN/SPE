package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 销售退货/贷项（V1024）。 */
@Data
@TableName("sales_return")
public class SalesReturn {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String returnNo;
  private Long companyId;
  private Long customerId;
  private Long shipmentId;
  private Long orderId;
  private String currency;
  private SalesReturnStatus status;
  private BigDecimal netAmount;
  private BigDecimal taxAmount;
  private BigDecimal totalAmount;
  private String reason;
  private LocalDateTime creditedAt;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
}

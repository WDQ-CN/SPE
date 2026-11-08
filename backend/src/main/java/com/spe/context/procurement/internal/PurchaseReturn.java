package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 采购退货（V1012；docs/erp/32 §5）。 */
@Data
@TableName("purchase_return")
public class PurchaseReturn {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String docNo;
  private Long companyId;
  private Long supplierId;
  private Long poId;
  private Long warehouseId;
  private LocalDate returnDate;
  private String currency;
  private BigDecimal netAmount;
  private Boolean invoiced;
  private String status;
  private String reason;
  private Long createdBy;
  private Integer version;
  private LocalDateTime createdAt;
}

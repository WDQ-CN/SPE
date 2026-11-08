package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.procurement.api.GoodsReceiptStatus;
import java.time.LocalDateTime;
import lombok.Data;

/** 收货单（V1008；docs/erp/32、48）。 */
@Data
@TableName("goods_receipt")
public class GoodsReceipt {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String docNo;
  private Long poId;
  private Long companyId;
  private Long warehouseId;
  private GoodsReceiptStatus status;
  private Long createdBy;
  private LocalDateTime receivedAt;
  private Long reversedBy;
  private LocalDateTime reversedAt;
  private String reverseReason;
  private Integer version;
}

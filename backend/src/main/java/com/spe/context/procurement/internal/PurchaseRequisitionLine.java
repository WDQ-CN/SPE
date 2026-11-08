package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 请购单行（V1008）。 */
@Data
@TableName("purchase_requisition_line")
public class PurchaseRequisitionLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long requisitionId;
  private Integer lineNo;
  private Long itemId;
  private BigDecimal qty;
  private String uom;
  private BigDecimal estimatedPrice;
  private BigDecimal amount;
}

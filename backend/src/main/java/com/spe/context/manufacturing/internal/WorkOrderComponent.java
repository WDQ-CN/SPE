package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 工单组件：下达时从 BOM 冻结。 */
@Data
@TableName("work_order_component")
public class WorkOrderComponent {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long workOrderId;
  private Integer lineNo;
  private Long bomLineId;
  private Long componentItemId;
  private BigDecimal qtyRequired;
  private String uom;
  private BigDecimal issuedQty;
}

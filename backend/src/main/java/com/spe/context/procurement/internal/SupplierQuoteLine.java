package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 供应商报价行。 */
@Data
@TableName("supplier_quote_line")
public class SupplierQuoteLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long quoteId;
  private Long rfqLineId;
  private BigDecimal unitPrice;
  private BigDecimal taxRate;
  private BigDecimal taxAmount;
  private Integer leadTimeDays;
  private BigDecimal moq;
  private BigDecimal qtyAvailable;
  private String incoterm;
  private String remark;
}

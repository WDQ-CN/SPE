package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 收款核销。 */
@Data
@TableName("receipt_allocation")
public class ReceiptAllocation {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long receiptId;
  private Long invoiceId;
  private BigDecimal amount;
}

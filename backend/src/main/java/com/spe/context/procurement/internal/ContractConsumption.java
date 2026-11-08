package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 合同消耗（PO 释放记录）。 */
@Data
@TableName("contract_consumption")
public class ContractConsumption {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long contractLineId;
  private Long poId;
  private Long poLineId;
  private BigDecimal qty;
  private BigDecimal amount;
  private LocalDateTime consumedAt;
}

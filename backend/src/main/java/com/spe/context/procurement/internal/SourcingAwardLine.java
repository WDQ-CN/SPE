package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 定标行。 */
@Data
@TableName("sourcing_award_line")
public class SourcingAwardLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long awardId;
  private Long rfqLineId;
  private Long quoteLineId;
  private BigDecimal awardedQty;
  private BigDecimal unitPrice;
  private BigDecimal amount;
}

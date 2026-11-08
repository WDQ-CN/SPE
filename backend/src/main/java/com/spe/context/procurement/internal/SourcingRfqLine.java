package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 询价行。 */
@Data
@TableName("sourcing_rfq_line")
public class SourcingRfqLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long rfqId;
  private Integer lineNo;
  private Long itemId;
  private BigDecimal qty;
  private String uom;
  private LocalDate needDate;
  private BigDecimal targetPrice;
  private String spec;
}

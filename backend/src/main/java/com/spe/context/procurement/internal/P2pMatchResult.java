package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 三单匹配结果（V1009；一发票一行，重跑匹配更新本行）。 */
@Data
@TableName("p2p_match_result")
public class P2pMatchResult {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long invoiceId;
  private MatchStatus status;
  private BigDecimal priceVariance;
  private BigDecimal qtyVariance;
  private BigDecimal amountVariance;
  private String detail;
}

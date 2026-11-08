package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.math.BigDecimal;
import lombok.Data;

/** 三单匹配容差（V1009；docs/erp/50 §3）。 */
@Data
@TableName("p2p_tolerance")
public class P2pTolerance {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private MatchDimension dimension;
  private BigDecimal percent;
  private BigDecimal absolute;
  private MasterDataStatus status;
}

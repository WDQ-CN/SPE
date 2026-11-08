package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 合同行。 */
@Data
@TableName("purchase_contract_line")
public class PurchaseContractLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long contractId;
  private Integer lineNo;
  private Long itemId;
  private String uom;
  private BigDecimal price;
  private BigDecimal minQty;
  private BigDecimal maxQty;
  private Boolean taxIncluded;
  private LocalDate validFrom;
  private LocalDate validTo;
}

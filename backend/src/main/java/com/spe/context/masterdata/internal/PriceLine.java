package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 价格表行（V1007；docs/erp/31、48）。 */
@Data
@TableName("price_line")
public class PriceLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long priceListId;
  private Long itemId;
  private String uom;
  private BigDecimal minQty;
  private BigDecimal price;
  private Boolean taxIncluded;
  private LocalDate validFrom;
  private LocalDate validTo;
  private Integer version;
}

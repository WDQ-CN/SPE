package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 物料计量单位换算（V1006；docs/erp/31、48）。 */
@Data
@TableName("item_uom_conversion")
public class ItemUomConversion {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long itemId;
  private String fromUom;
  private String toUom;
  private BigDecimal numerator;
  private BigDecimal denominator;
  private Integer version;
}

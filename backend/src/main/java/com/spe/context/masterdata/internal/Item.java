package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.ItemType;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.ValuationMethod;
import lombok.Data;

/** 物料/服务主数据（V1006；docs/erp/31、48）。 */
@Data
@TableName("item")
public class Item {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String code;
  private String name;
  private String spec;
  private ItemType itemType;
  private String baseUom;
  private Boolean lotControlled;
  private Boolean serialControlled;
  private ValuationMethod valuationMethod;
  private java.math.BigDecimal standardCost;
  private MasterDataStatus status;
  private Integer version;
}

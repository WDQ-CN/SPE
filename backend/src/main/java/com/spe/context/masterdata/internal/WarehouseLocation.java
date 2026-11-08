package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import lombok.Data;

/** 仓库库位（V1013；docs/erp/31、33）。 */
@Data
@TableName("warehouse_location")
public class WarehouseLocation {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long warehouseId;
  private String code;
  private String name;
  private String zone;
  private String aisle;
  private String rack;
  private String levelNo;
  private String binNo;
  private MasterDataStatus status;
  private Integer version;
}

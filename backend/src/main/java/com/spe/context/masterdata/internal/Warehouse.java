package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.WarehouseType;
import lombok.Data;

/** 仓库（V1006；docs/erp/31、33、48）。 */
@Data
@TableName("warehouse")
public class Warehouse {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long plantId;
  private String code;
  private String name;
  private WarehouseType warehouseType;
  private MasterDataStatus status;
  private Integer version;
}

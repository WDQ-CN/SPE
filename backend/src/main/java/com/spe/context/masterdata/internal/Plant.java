package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import lombok.Data;

/** 工厂（V1006；docs/erp/31、48）。 */
@Data
@TableName("plant")
public class Plant {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private String code;
  private String name;
  private String address;
  private MasterDataStatus status;
  private Integer version;
}

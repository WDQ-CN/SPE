package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import lombok.Data;

/** 公司/核算主体（V1006；docs/erp/31、48）。 */
@Data
@TableName("company")
public class Company {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String code;
  private String name;
  private String baseCurrency;
  private String taxNo;
  private String country;
  private MasterDataStatus status;
  private Integer version;
}

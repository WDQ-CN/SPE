package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.AccountType;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.NormalBalance;
import lombok.Data;

/** 会计科目（V1007；docs/erp/34、48）。 */
@Data
@TableName("gl_account")
public class GlAccount {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private String code;
  private String name;
  private AccountType accountType;
  private NormalBalance normalBalance;
  private Boolean requiresCostCenter;
  private MasterDataStatus status;
  private Integer version;
}

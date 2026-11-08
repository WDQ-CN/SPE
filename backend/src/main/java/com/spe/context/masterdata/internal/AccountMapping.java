package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.time.LocalDate;
import lombok.Data;

/** 业务事件到会计科目的映射（V1007；docs/erp/34、48）。 */
@Data
@TableName("account_mapping")
public class AccountMapping {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private String eventType;
  private String dimension;
  private Long debitAccountId;
  private Long creditAccountId;
  private Long taxAccountId;
  private LocalDate validFrom;
  private MasterDataStatus status;
  private Integer version;
}

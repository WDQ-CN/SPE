package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.TaxType;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 税码（V1007；docs/erp/34、48）。 */
@Data
@TableName("tax_code")
public class TaxCode {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String code;
  private String country;
  private BigDecimal rate;
  private TaxType taxType;
  private LocalDate effectiveFrom;
  private MasterDataStatus status;
  private Integer version;
}

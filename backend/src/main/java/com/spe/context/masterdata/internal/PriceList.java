package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.PriceListType;
import java.time.LocalDate;
import lombok.Data;

/** 价格表（V1007；docs/erp/31）。 */
@Data
@TableName("price_list")
public class PriceList {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String code;
  private PriceListType listType;
  private String currency;
  private LocalDate validFrom;
  private LocalDate validTo;
  private MasterDataStatus status;
  private Integer version;
}

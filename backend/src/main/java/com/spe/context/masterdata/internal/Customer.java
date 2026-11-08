package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import java.math.BigDecimal;
import lombok.Data;

/** 客户主数据（V1007；docs/erp/31、48）。 */
@Data
@TableName("customer")
public class Customer {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String code;
  private String name;
  private String taxNo;
  private String address;
  private String contactName;
  private String contactPhone;
  private String currency;
  private String paymentTerms;
  private BigDecimal creditLimit;
  private BigDecimal creditUsed;
  private MasterDataStatus status;
  private Integer version;
}

package com.spe.context.masterdata.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.QualificationStatus;
import java.time.LocalDate;
import lombok.Data;

/** 供应商扩展（V1007；docs/erp/31、48）。 */
@Data
@TableName("supplier_extension")
public class SupplierExtension {

  @TableId(type = IdType.INPUT)
  private Long supplierId;

  private String taxNo;
  private String bankAccount;
  private String bankName;
  private String paymentTerms;
  private String currency;
  private String incoterms;
  private QualificationStatus qualificationStatus;
  private LocalDate certificateExpiry;
  private Integer version;
}

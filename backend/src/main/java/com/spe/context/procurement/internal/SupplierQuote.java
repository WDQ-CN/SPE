package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 供应商报价（V1017；docs/erp/55 §3.2）。 */
@Data
@TableName("supplier_quote")
public class SupplierQuote {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String quoteNo;
  private Long rfqId;
  private Long supplierId;
  private String currency;
  private LocalDate validUntil;
  private SupplierQuoteStatus status;
  private LocalDateTime submittedAt;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
}

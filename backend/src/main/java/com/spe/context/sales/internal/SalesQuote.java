package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 销售报价（V1021；docs/erp/35）。 */
@Data
@TableName("sales_quote")
public class SalesQuote {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String quoteNo;
  private Long companyId;
  private Long customerId;
  private String currency;
  private LocalDate validUntil;
  private SalesQuoteStatus status;
  private BigDecimal netAmount;
  private BigDecimal taxAmount;
  private BigDecimal totalAmount;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime sentAt;
  private LocalDateTime acceptedAt;
  private Long convertedOrderId;
}

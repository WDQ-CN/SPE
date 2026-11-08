package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 供应商价格库（V1018）。 */
@Data
@TableName("supplier_price")
public class SupplierPrice {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private Long supplierId;
  private Long itemId;
  private String uom;
  private BigDecimal minQty;
  private BigDecimal price;
  private Boolean taxIncluded;
  private String currency;
  private LocalDate validFrom;
  private LocalDate validTo;
  private SupplierPriceSource sourceType;
  private Long contractId;
  private Long awardId;
  private SupplierPriceStatus status;
  private Integer version;
}

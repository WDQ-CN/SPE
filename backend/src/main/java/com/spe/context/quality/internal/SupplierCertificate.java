package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 供应商证书。 */
@Data
@TableName("supplier_certificate")
public class SupplierCertificate {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private Long supplierId;
  private Long itemId;
  private String certType;
  private String certNo;
  private LocalDate validFrom;
  private LocalDate validTo;
  private CertificateStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
}

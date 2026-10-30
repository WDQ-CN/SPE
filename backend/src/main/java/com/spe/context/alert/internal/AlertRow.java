package com.spe.context.alert.internal;

import java.time.LocalDateTime;
import lombok.Data;

/** 预警列表行（join supplier 与 sys_user 的展示投影，本上下文内使用）。 */
@Data
public class AlertRow {

  private Long id;

  private Long taskSupplierId;

  private Long supplierId;

  private String supplierCode;

  private String supplierName;

  private Integer year;

  private Integer quarter;

  private String type;

  private String title;

  private String content;

  private String status;

  private Long handledBy;

  private String handledByName;

  private String handledComment;

  private LocalDateTime handledAt;

  private LocalDateTime createdAt;
}

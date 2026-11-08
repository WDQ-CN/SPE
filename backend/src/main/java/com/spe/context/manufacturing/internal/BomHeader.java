package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** BOM 头（V1026）。 */
@Data
@TableName("bom_header")
public class BomHeader {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String bomNo;
  private Long companyId;
  private Long itemId;
  private String version;
  private BomStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime activatedAt;
}

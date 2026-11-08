package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 库存盘点（V1016；docs/erp/54 §3.4）。 */
@Data
@TableName("inventory_count")
public class InventoryCount {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String countNo;
  private Long companyId;
  private Long warehouseId;
  private InventoryCountStatus status;
  private LocalDateTime snapshotAt;
  private Long countedBy;
  private Long reviewedBy;
  private LocalDateTime reviewedAt;
  private Long postedBy;
  private LocalDateTime postedAt;
  private String reason;
  private Integer version;
  private LocalDateTime createdAt;
}

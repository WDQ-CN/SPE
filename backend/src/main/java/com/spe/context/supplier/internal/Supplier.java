package com.spe.context.supplier.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.supplier.api.SupplierStatus;
import lombok.Data;

/**
 * 供应商实体（`V3__supplier.sql` / `V6__supplier_category_nullable.sql` / 02 §3）。
 *
 * <p>`category` 可空（E10/`V6`）；枚举列按 MyBatis 默认处理器落库为 `name()` 字符串，与
 * `VARCHAR(20)` 存大写常量的约定一致（06 §4）。本实体只在 supplier 上下文内流转，**不出 Service 层**。
 *
 * <p>不含 `created_at`/`updated_at`：P1 无读取它们的场景，按需再加（与 `SysUser` 同口径）。
 */
@Data
@TableName("supplier")
public class Supplier {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** 业务唯一编码，`uk_supplier_code`；允许编辑（E17）。 */
  private String code;

  private String name;

  /** 分类：自由文本、可空（V3/V6 已定「P1 不做字典表」）。 */
  private String category;

  private SupplierStatus status;

  /** 乐观锁版本：编辑与启停都以它做守卫（06 §2.4）。 */
  private Integer version;
}

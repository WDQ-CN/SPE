package com.spe.context.system.internal.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserStatus;
import lombok.Data;

/**
 * 系统用户实体（`V1__sys_user.sql` / 02 §3）。
 *
 * <p>枚举列按 MyBatis 默认的 {@code EnumTypeHandler} 落库为 `name()` 字符串，与 `VARCHAR(20)`
 * 存大写常量的约定一致（06 §4）。本实体只在 system 上下文内流转，**不出 Service 层**（06 §2.7）。
 *
 * <p>不含 `created_at`/`updated_at`：P1 无读取它们的场景，按需再加（避免未被使用的字段）。
 */
@Data
@TableName("sys_user")
public class SysUser {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String username;

  private String passwordHash;

  private String name;

  private Role role;

  private Department department;

  private UserStatus status;

  /** 乐观锁版本：一切状态迁移与档案修改都以它 + 前置条件做守卫（06 §2.4）。 */
  private Integer version;
}

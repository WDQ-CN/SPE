package com.spe.context.template.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 模板实体（`V7__template.sql` / 02 §3）。
 *
 * <p>模板本身只是「名称 + 一组版本」的壳：维度与指标挂在 {@code template_version} 下，不在它下面——
 * 这是「任务绑定已发布版本」（05 ADR-3）得以成立的前提。
 *
 * <p>不含 `created_at`/`updated_at`：P2 无读取它们的场景，按需再加（与 `Supplier`/`SysUser` 同口径）。
 * 也不给名称建唯一键：01 未要求名称唯一，不替需求加约束（12 §E2）。本实体只在 template 上下文内流转，
 * **不出 Service 层**。
 */
@Data
@TableName("template")
public class Template {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;
}

package com.spe.context.system.internal.setting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 系统设置键值实体（`V2__sys_setting.sql`）。
 *
 * <p>`setting_value` 是 JSON 列，此处按 String 承载原文，序列化/反序列化由 Service 用 Jackson 完成——
 * 让「这条设置的值是什么结构」只在 Service 里定义一次。表**不设 version**（仅 ADMIN 可写、并发风险低）。
 */
@Data
@TableName("sys_setting")
public class SysSetting {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String settingKey;

  private String settingValue;
}

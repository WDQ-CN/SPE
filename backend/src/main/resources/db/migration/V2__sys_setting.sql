-- V2：系统设置键值表（02 §3 的 key-value JSON 口径）。脚本合入后不可修改，变更一律新脚本（06 §1.3）。
-- 仅 ADMIN 可写、并发风险低，故不设 version 列（06 §4 的「按需」约定）。
CREATE TABLE sys_setting (
  id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  setting_key   VARCHAR(64) NOT NULL COMMENT '设置项键，如 grade.thresholds',
  setting_value JSON        NOT NULL COMMENT '设置项值（JSON）',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_setting_key (setting_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '系统设置';

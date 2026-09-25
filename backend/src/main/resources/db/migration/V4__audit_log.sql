-- V4：操作日志（01 SM5、05 Q8）。只追加，不设 version。
-- 查询索引待 SM5 的查询页按 EXPLAIN 结果另行补迁移（06 §4：新查询先 EXPLAIN 再加索引）。
CREATE TABLE audit_log (
  id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  operator_id   BIGINT      NULL COMMENT '操作人 id；匿名或登录失败时为空',
  operator_name VARCHAR(50) NULL COMMENT '操作人账号，便于失败登录留痕',
  action        VARCHAR(64) NOT NULL COMMENT '动作码：LOGIN_SUCCESS/LOGIN_FAILED/USER_CREATE/...',
  target_type   VARCHAR(32) NULL COMMENT '目标类型：USER/SUPPLIER/SETTING',
  target_id     BIGINT      NULL COMMENT '目标主键',
  detail        JSON        NULL COMMENT '业务可复述的细节；禁止写入口令、令牌（06 §2.8）',
  ip            VARCHAR(45) NULL COMMENT '来源 IP（IPv6 最长 45 字符）',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间',
  PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志';

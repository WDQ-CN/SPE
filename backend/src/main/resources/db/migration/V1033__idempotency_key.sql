-- E6 数据平台：持久化幂等键（docs/erp/42 §3/§6）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

CREATE TABLE idempotency_key (
  scope           VARCHAR(200) NOT NULL COMMENT '方法+路径+查询串',
  idem_key        VARCHAR(64)  NOT NULL COMMENT 'Idempotency-Key',
  request_hash    CHAR(64)     NOT NULL COMMENT '请求体 SHA-256',
  response_status INT          NOT NULL DEFAULT 0 COMMENT '0=处理中，其余为 HTTP 状态',
  response_body   LONGTEXT     NULL COMMENT '成功响应体（JSON）',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  expires_at      DATETIME(3)  NOT NULL COMMENT '过期时间',
  PRIMARY KEY (scope, idem_key),
  KEY idx_idempotency_expiry (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '外部写请求幂等键';

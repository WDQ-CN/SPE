-- E6 治理：SoD 规则/例外 + 审计 traceId（docs/erp/41 §3/§4/§7）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

ALTER TABLE audit_log
  ADD COLUMN trace_id VARCHAR(64) NULL COMMENT '链路 ID';

CREATE TABLE sod_rule (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code        VARCHAR(64)  NOT NULL COMMENT '规则编码',
  role_a      VARCHAR(32)  NOT NULL COMMENT '冲突角色 A',
  role_b      VARCHAR(32)  NOT NULL COMMENT '冲突角色 B',
  description VARCHAR(255) NOT NULL COMMENT '冲突说明',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  created_by  BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sod_rule_code (code),
  KEY idx_sod_rule_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '职责分离规则';

CREATE TABLE sod_exception (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id      BIGINT       NOT NULL COMMENT '被授权用户',
  rule_id      BIGINT       NOT NULL COMMENT '规则',
  reason       VARCHAR(500) NOT NULL COMMENT '例外原因',
  valid_from   DATE         NOT NULL COMMENT '生效日',
  valid_to     DATE         NOT NULL COMMENT '失效日',
  status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
               COMMENT 'PENDING/APPROVED/REJECTED/EXPIRED',
  requested_by BIGINT       NOT NULL DEFAULT 0 COMMENT '申请人',
  approved_by  BIGINT       NULL COMMENT '审批人',
  approved_at  DATETIME(3)  NULL COMMENT '审批时间',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  version      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  KEY idx_sod_exception_user (user_id, status, valid_from, valid_to),
  KEY idx_sod_exception_rule (rule_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '职责分离例外';

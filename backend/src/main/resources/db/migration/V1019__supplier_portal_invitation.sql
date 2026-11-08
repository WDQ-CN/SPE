-- E2 门户邀请 Token（docs/erp/56 §2/§5）。
-- 只增不改：应用后本文件冻结（06 §1.3）。只存 Token 的 SHA-256，不落明文。

CREATE TABLE supplier_portal_invitation (
  id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  supplier_id   BIGINT      NOT NULL COMMENT '供应商',
  token_hash    CHAR(64)    NOT NULL COMMENT '邀请 Token 的 SHA-256',
  status        VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                COMMENT 'PENDING/ACCEPTED/REVOKED/EXPIRED',
  expires_at    DATETIME(3) NOT NULL COMMENT '过期时间',
  created_by    BIGINT      NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  accepted_at   DATETIME(3) NULL COMMENT '接受时间',
  portal_user_id BIGINT     NULL COMMENT '接受后创建的门户账号',
  PRIMARY KEY (id),
  UNIQUE KEY uk_portal_invitation_token (token_hash),
  KEY idx_portal_invitation_supplier (supplier_id, status, expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商门户邀请';

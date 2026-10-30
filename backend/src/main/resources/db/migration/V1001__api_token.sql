-- M6（23 §2 D2）：开放 API Token。只存 SHA-256 哈希与展示用 prefix，明文只在生成响应出现一次。
-- 版本必须 >1000：demo 已有 V999/V1000，V1001 在两种 profile 下都排在它们之后（04-121 的 out-of-order 教训）。
CREATE TABLE api_token (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name         VARCHAR(100) NOT NULL COMMENT 'Token 名称（给人看，不唯一）',
  token_hash   CHAR(64)     NOT NULL COMMENT 'SHA-256 hex；鉴权按此唯一查找',
  token_prefix VARCHAR(20)  NOT NULL COMMENT '展示用前缀（spet_ + 前 8 位随机串）',
  status       VARCHAR(20)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED；停用不可逆',
  last_used_at DATETIME(3)  NULL COMMENT '最近一次鉴权成功时间',
  created_by   BIGINT       NOT NULL COMMENT '创建人 sys_user.id',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version      INT          NOT NULL DEFAULT 0 COMMENT '停用乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_api_token_hash (token_hash),
  KEY idx_api_token_status (status),
  CONSTRAINT ck_api_token_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '开放 API Token（M6）';

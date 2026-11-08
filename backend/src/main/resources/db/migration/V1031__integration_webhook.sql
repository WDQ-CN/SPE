-- E6 集成：outbox 事件、Webhook 订阅与投递（docs/erp/40 §3/§5）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

CREATE TABLE event_subscription (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id  BIGINT       NOT NULL COMMENT '公司',
  url         VARCHAR(500) NOT NULL COMMENT 'Webhook URL',
  secret      VARCHAR(128) NOT NULL COMMENT 'HMAC 密钥',
  event_types VARCHAR(500) NOT NULL COMMENT '订阅事件类型，逗号分隔',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  created_by  BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  version     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  KEY idx_event_subscription (company_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '事件订阅';

CREATE TABLE outbox_event (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  event_type      VARCHAR(64)  NOT NULL COMMENT '事件类型',
  aggregate_id    BIGINT       NULL COMMENT '聚合 ID',
  payload         LONGTEXT     NOT NULL COMMENT '事件载荷 JSON',
  status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING/SENT/FAILED/DEAD',
  attempts        INT          NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
  last_error      VARCHAR(500) NULL COMMENT '最后错误',
  next_attempt_at DATETIME(3)  NULL COMMENT '下次尝试时间',
  trace_id        VARCHAR(64)  NULL COMMENT '链路 ID',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  sent_at         DATETIME(3)  NULL COMMENT '投递完成时间',
  PRIMARY KEY (id),
  KEY idx_outbox_dispatch (status, next_attempt_at, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '事务性 outbox';

CREATE TABLE webhook_delivery (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  outbox_id         BIGINT       NOT NULL COMMENT 'outbox 事件',
  subscription_id   BIGINT       NOT NULL COMMENT '订阅',
  status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                    COMMENT 'PENDING/SENT/FAILED',
  attempts          INT          NOT NULL DEFAULT 0 COMMENT '尝试次数',
  response_status   INT          NULL COMMENT 'HTTP 状态',
  last_error        VARCHAR(500) NULL COMMENT '错误',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  delivered_at      DATETIME(3)  NULL COMMENT '成功时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_webhook_delivery (outbox_id, subscription_id),
  KEY idx_webhook_delivery_status (status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'Webhook 投递';

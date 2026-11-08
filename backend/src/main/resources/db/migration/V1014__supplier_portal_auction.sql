-- E2：供应商门户认证与竞价（docs/erp/55、56）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- 门户身份与内部 sys_user 分离；竞价出价按 lot 行锁串行裁决。

CREATE TABLE supplier_portal_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  supplier_id   BIGINT       NOT NULL COMMENT '供应商',
  login_name    VARCHAR(64)  NOT NULL COMMENT '门户登录名，全局唯一',
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 口令哈希',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  failed_attempts INT        NOT NULL DEFAULT 0 COMMENT '连续失败次数（登录限流辅助）',
  locked_until  DATETIME(3)  NULL COMMENT '锁定截止时间',
  last_login_at DATETIME(3)  NULL COMMENT '最后登录时间',
  version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_portal_login_name (login_name),
  KEY idx_portal_supplier (supplier_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商门户账号';

CREATE TABLE supplier_portal_refresh_token (
  id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  portal_user_id BIGINT     NOT NULL COMMENT '门户账号',
  token_hash    CHAR(64)    NOT NULL COMMENT 'SHA-256 哈希',
  expires_at    DATETIME(3) NOT NULL COMMENT '过期时间',
  revoked_at    DATETIME(3) NULL COMMENT '撤销时间',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_portal_refresh_hash (token_hash),
  KEY idx_portal_refresh_user (portal_user_id, expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商门户刷新令牌';

CREATE TABLE auction_event (
  id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  auction_no           VARCHAR(40)  NOT NULL COMMENT '竞价单号',
  company_id           BIGINT       NOT NULL COMMENT '公司',
  title                VARCHAR(128) NOT NULL COMMENT '标题',
  currency             CHAR(3)      NOT NULL COMMENT '币种',
  sealed               TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否密封',
  anti_sniping_seconds INT          NOT NULL DEFAULT 60 COMMENT '防狙击延时秒',
  max_extensions       INT          NOT NULL DEFAULT 3 COMMENT '最大延期次数',
  start_at             DATETIME(3)  NOT NULL COMMENT '开始时间',
  end_at               DATETIME(3)  NOT NULL COMMENT '结束时间',
  extension_count      INT          NOT NULL DEFAULT 0 COMMENT '已延期次数',
  result_visibility    VARCHAR(16)  NOT NULL DEFAULT 'ANONYMOUS' COMMENT 'ANONYMOUS/PUBLIC/NONE',
  status               VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                       COMMENT 'DRAFT/SCHEDULED/LIVE/CLOSED/AWARDED/CANCELED',
  created_by           BIGINT       NOT NULL COMMENT '创建人（内部）',
  version              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_auction_no (auction_no),
  KEY idx_auction_company_status (company_id, status, end_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '竞价事件';

CREATE TABLE auction_lot (
  id                     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  auction_id             BIGINT        NOT NULL COMMENT '竞价事件',
  line_no                INT           NOT NULL COMMENT '行号',
  item_id                BIGINT        NOT NULL COMMENT '物料',
  qty                    DECIMAL(19,6) NOT NULL COMMENT '数量',
  uom                    VARCHAR(16)   NOT NULL COMMENT '单位',
  start_price            DECIMAL(19,4) NOT NULL COMMENT '起拍价',
  reserve_price          DECIMAL(19,4) NULL COMMENT '保留价（内部）',
  min_decrement          DECIMAL(19,4) NOT NULL COMMENT '最小降幅',
  current_lowest_amount  DECIMAL(19,4) NULL COMMENT '当前最低有效报价',
  current_lowest_bid_id  BIGINT        NULL COMMENT '当前最低报价 id',
  bid_count              INT           NOT NULL DEFAULT 0 COMMENT '有效出价次数',
  version                INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_auction_lot (auction_id, line_no),
  KEY idx_auction_lot_item (item_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '竞价批次';

CREATE TABLE auction_invitation (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  auction_id   BIGINT      NOT NULL COMMENT '竞价事件',
  supplier_id  BIGINT      NOT NULL COMMENT '供应商',
  status       VARCHAR(16) NOT NULL DEFAULT 'INVITED' COMMENT 'INVITED/ACCEPTED/DECLINED',
  invited_by   BIGINT      NOT NULL COMMENT '邀请人（内部）',
  invited_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '邀请时间',
  accepted_at  DATETIME(3) NULL COMMENT '接受时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_auction_invitation (auction_id, supplier_id),
  KEY idx_invitation_supplier (supplier_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '竞价邀请';

CREATE TABLE auction_bid (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  auction_id      BIGINT        NOT NULL COMMENT '竞价事件',
  lot_id          BIGINT        NOT NULL COMMENT '竞价批次',
  supplier_id     BIGINT        NOT NULL COMMENT '供应商',
  amount          DECIMAL(19,4) NOT NULL COMMENT '出价金额',
  status          VARCHAR(16)   NOT NULL DEFAULT 'VALID' COMMENT 'VALID/OUTBID/WINNING/DISQUALIFIED',
  idempotency_key VARCHAR(64)   NOT NULL COMMENT '幂等键',
  request_hash    CHAR(64)      NOT NULL COMMENT '请求哈希',
  below_reserve   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '低于保留价（内部）',
  client_ip       VARCHAR(64)   NULL COMMENT '来源 IP',
  submitted_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bid_idempotency (lot_id, supplier_id, idempotency_key),
  KEY idx_bid_auction_supplier (auction_id, supplier_id, submitted_at),
  KEY idx_bid_lot_amount (lot_id, amount, submitted_at),
  CONSTRAINT ck_bid_amount CHECK (amount > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '竞价出价（不可变）';

CREATE TABLE auction_extension (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  auction_id  BIGINT      NOT NULL COMMENT '竞价事件',
  lot_id      BIGINT      NULL COMMENT '批次；NULL=整个事件',
  extended_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '延期时间',
  old_end_at  DATETIME(3) NOT NULL COMMENT '原结束时间',
  new_end_at  DATETIME(3) NOT NULL COMMENT '新结束时间',
  reason      VARCHAR(200) NULL COMMENT '原因',
  PRIMARY KEY (id),
  KEY idx_extension_auction (auction_id, extended_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '竞价防狙击延期记录';

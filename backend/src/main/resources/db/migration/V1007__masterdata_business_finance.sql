-- E1-W1b：业务与财务主数据（docs/erp/31、34、48、52）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- 不使用外键：沿用项目既有约定，应用层校验 + 索引；组织/供应商/物料引用只存 id。

CREATE TABLE supplier_extension (
  supplier_id          BIGINT       NOT NULL COMMENT '供应商 id（1:1）',
  tax_no               VARCHAR(64)  NULL COMMENT '税号（敏感，展示脱敏）',
  bank_account         VARCHAR(64)  NULL COMMENT '银行账号（敏感，展示脱敏）',
  bank_name            VARCHAR(128) NULL COMMENT '开户行',
  payment_terms        VARCHAR(32)  NULL COMMENT '付款条件/账期',
  currency             CHAR(3)      NULL COMMENT '默认币种',
  incoterms            VARCHAR(16)  NULL COMMENT '贸易术语',
  qualification_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                       COMMENT 'PENDING/APPROVED/BLOCKED',
  certificate_expiry   DATE         NULL COMMENT '资质到期日',
  version              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (supplier_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商扩展（商务/资质）';

CREATE TABLE customer (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  code           VARCHAR(50)   NOT NULL COMMENT '客户编码，全局唯一',
  name           VARCHAR(200)  NOT NULL COMMENT '客户名称',
  tax_no         VARCHAR(64)   NULL COMMENT '税号（敏感，展示脱敏）',
  address        VARCHAR(255)  NULL COMMENT '地址',
  contact_name   VARCHAR(64)   NULL COMMENT '联系人',
  contact_phone  VARCHAR(32)   NULL COMMENT '联系电话',
  currency       CHAR(3)       NOT NULL DEFAULT 'CNY' COMMENT '默认币种',
  payment_terms  VARCHAR(32)   NULL COMMENT '账期',
  credit_limit   DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '信用额度',
  credit_used    DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '已用信用',
  status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                 ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_customer_code (code),
  KEY idx_customer_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '客户';

CREATE TABLE price_list (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  code        VARCHAR(50) NOT NULL COMMENT '价格表编码，全局唯一',
  list_type   VARCHAR(16) NOT NULL COMMENT 'SALES/PURCHASE',
  currency    CHAR(3)     NOT NULL COMMENT '币种',
  valid_from  DATE        NOT NULL COMMENT '生效日期',
  valid_to    DATE        NULL COMMENT '失效日期（可空）',
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version     INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_list_code (code),
  KEY idx_price_list_type_status (list_type, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '价格表';

CREATE TABLE price_line (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  price_list_id BIGINT        NOT NULL COMMENT '价格表',
  item_id       BIGINT        NOT NULL COMMENT '物料',
  uom           VARCHAR(16)   NOT NULL COMMENT '计量单位',
  min_qty       DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '数量阶梯下限',
  price         DECIMAL(19,4) NOT NULL COMMENT '单价',
  tax_included  TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否含税',
  valid_from    DATE          NOT NULL COMMENT '生效日期',
  valid_to      DATE          NULL COMMENT '失效日期',
  version       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_line (price_list_id, item_id, uom, min_qty, valid_from),
  KEY idx_price_line_item (item_id),
  CONSTRAINT ck_price_line_price CHECK (price >= 0),
  CONSTRAINT ck_price_line_min_qty CHECK (min_qty >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '价格表行';

CREATE TABLE tax_code (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code           VARCHAR(32)  NOT NULL COMMENT '税码，全局唯一',
  country        CHAR(2)      NOT NULL COMMENT '国家/地区',
  rate           DECIMAL(9,6) NOT NULL COMMENT '税率（0~1）',
  tax_type       VARCHAR(16)  NOT NULL COMMENT 'INPUT/OUTPUT',
  effective_from DATE         NOT NULL COMMENT '生效日期',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                 ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tax_code (code),
  CONSTRAINT ck_tax_rate CHECK (rate >= 0 AND rate <= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '税码';

CREATE TABLE gl_account (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id          BIGINT       NOT NULL COMMENT '公司',
  code                VARCHAR(32)  NOT NULL COMMENT '科目编码，公司内唯一',
  name                VARCHAR(128) NOT NULL COMMENT '科目名称',
  account_type        VARCHAR(16)  NOT NULL COMMENT 'ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE',
  normal_balance      VARCHAR(16)  NOT NULL COMMENT 'DEBIT/CREDIT',
  requires_cost_center TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否要求成本中心',
  status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gl_account_company_code (company_id, code),
  KEY idx_gl_account_company (company_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '会计科目';

CREATE TABLE account_mapping (
  id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id        BIGINT      NOT NULL COMMENT '公司',
  event_type        VARCHAR(64) NOT NULL COMMENT '业务事件类型',
  dimension         VARCHAR(64) NOT NULL DEFAULT '' COMMENT '维度（空串表示无维度）',
  debit_account_id  BIGINT      NOT NULL COMMENT '借方科目',
  credit_account_id BIGINT      NOT NULL COMMENT '贷方科目',
  tax_account_id    BIGINT      NULL COMMENT '税科目（可空）',
  valid_from        DATE        NOT NULL COMMENT '生效日期',
  status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version           INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_account_mapping (company_id, event_type, dimension, valid_from),
  KEY idx_account_mapping_company (company_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '业务事件到会计科目的映射';

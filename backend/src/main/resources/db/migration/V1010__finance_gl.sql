-- W5：最小总账/AP（docs/erp/34、52）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- 业务事件（GR/发票/付款）按科目映射自动过账；来源唯一键保证重复事件不重复入账。
-- E1 不做期间关闭与手工凭证，期间字段由 entry_date 承载，E3 扩展 fiscal_period。

CREATE TABLE finance_journal_entry (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_no       VARCHAR(40)   NOT NULL COMMENT '凭证号',
  company_id   BIGINT        NOT NULL COMMENT '公司（会计主体）',
  entry_date   DATE          NOT NULL COMMENT '记账日期',
  source_type  VARCHAR(32)   NOT NULL COMMENT '业务来源：GOODS_RECEIPT/SUPPLIER_INVOICE/PAYMENT',
  source_id    BIGINT        NOT NULL COMMENT '来源单据 id',
  currency     CHAR(3)       NOT NULL COMMENT '币种',
  description  VARCHAR(200)  NULL COMMENT '摘要',
  total_amount DECIMAL(19,4) NOT NULL COMMENT '借方/贷方合计',
  status       VARCHAR(16)   NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED（E1 直接过账）',
  created_by   BIGINT        NOT NULL COMMENT '过账操作人',
  posted_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '过账时间',
  version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_journal_doc_no (doc_no),
  UNIQUE KEY uk_journal_source (source_type, source_id),
  KEY idx_journal_company_date (company_id, entry_date),
  KEY idx_journal_company_source (company_id, source_type, entry_date),
  CONSTRAINT ck_journal_amount CHECK (total_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '会计凭证（来源幂等）';

CREATE TABLE finance_journal_line (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  entry_id    BIGINT        NOT NULL COMMENT '凭证',
  line_no     INT           NOT NULL COMMENT '行号',
  account_id  BIGINT        NOT NULL COMMENT '会计科目',
  debit       DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '借方金额',
  credit      DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '贷方金额',
  description VARCHAR(200)  NULL COMMENT '行摘要',
  PRIMARY KEY (id),
  UNIQUE KEY uk_journal_line (entry_id, line_no),
  KEY idx_journal_line_account (account_id),
  CONSTRAINT ck_journal_line_amount CHECK (debit >= 0 AND credit >= 0 AND (debit = 0 OR credit = 0))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '会计分录用';

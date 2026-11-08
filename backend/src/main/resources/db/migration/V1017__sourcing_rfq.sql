-- E2 寻源：RFQ / 供应商报价 / 定标（docs/erp/55）。
-- 只增不改：应用后本文件冻结（06 §1.3）。竞价表已在 V1014。

CREATE TABLE sourcing_rfq (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  rfq_no        VARCHAR(40)   NOT NULL COMMENT 'RFQ 单号',
  company_id    BIGINT        NOT NULL COMMENT '公司',
  plant_id      BIGINT        NOT NULL COMMENT '工厂',
  buyer_id      BIGINT        NOT NULL COMMENT '采购员',
  title         VARCHAR(200)  NOT NULL COMMENT '标题',
  currency      CHAR(3)       NOT NULL COMMENT '币种',
  deadline_at   DATETIME(3)   NOT NULL COMMENT '报价截止时间',
  sealed        TINYINT       NOT NULL DEFAULT 0 COMMENT '1=密封报价',
  status        VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'
                COMMENT 'DRAFT/SENT/CLOSED/EVALUATING/AWARDED/CANCELED',
  source_pr_id  BIGINT        NULL COMMENT '来源请购单',
  award_policy  VARCHAR(16)   NOT NULL DEFAULT 'WHOLE' COMMENT 'WHOLE/LINE/SPLIT',
  version       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by    BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  sent_at       DATETIME(3)   NULL COMMENT '发送时间',
  closed_at     DATETIME(3)   NULL COMMENT '关闭时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rfq_no (rfq_no),
  KEY idx_rfq_status (company_id, status, deadline_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '询价单（RFQ）';

CREATE TABLE sourcing_rfq_line (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  rfq_id       BIGINT        NOT NULL COMMENT 'RFQ',
  line_no      INT           NOT NULL COMMENT '行号',
  item_id      BIGINT        NOT NULL COMMENT '物料',
  qty          DECIMAL(19,6) NOT NULL COMMENT '数量',
  uom          VARCHAR(16)   NOT NULL COMMENT '单位',
  need_date    DATE          NULL COMMENT '需求日期',
  target_price DECIMAL(19,4) NULL COMMENT '目标单价',
  spec         VARCHAR(255)  NULL COMMENT '规格说明',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rfq_line (rfq_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '询价行';

CREATE TABLE sourcing_invitation (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  rfq_id       BIGINT      NOT NULL COMMENT 'RFQ',
  supplier_id  BIGINT      NOT NULL COMMENT '供应商',
  status       VARCHAR(16) NOT NULL DEFAULT 'INVITED'
               COMMENT 'INVITED/VIEWED/QUOTED/DECLINED',
  invited_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '邀请时间',
  viewed_at    DATETIME(3) NULL COMMENT '查看时间',
  responded_at DATETIME(3) NULL COMMENT '响应时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rfq_supplier (rfq_id, supplier_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '询价邀请';

CREATE TABLE supplier_quote (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  quote_no     VARCHAR(40)  NOT NULL COMMENT '报价单号',
  rfq_id       BIGINT       NOT NULL COMMENT 'RFQ',
  supplier_id  BIGINT       NOT NULL COMMENT '供应商',
  currency     CHAR(3)      NOT NULL COMMENT '币种',
  valid_until  DATE         NULL COMMENT '有效期至',
  status       VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED'
               COMMENT 'DRAFT/SUBMITTED/WITHDRAWN/ACCEPTED/REJECTED',
  submitted_at DATETIME(3)  NULL COMMENT '提交时间',
  version      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by   BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人（门户账号）',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
               ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_rfq_supplier (rfq_id, supplier_id),
  UNIQUE KEY uk_quote_no (quote_no),
  KEY idx_quote_status (rfq_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商报价';

CREATE TABLE supplier_quote_line (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  quote_id       BIGINT        NOT NULL COMMENT '报价',
  rfq_line_id    BIGINT        NOT NULL COMMENT 'RFQ 行',
  unit_price     DECIMAL(19,4) NOT NULL COMMENT '未税单价',
  tax_rate       DECIMAL(9,6)  NOT NULL DEFAULT 0 COMMENT '税率',
  tax_amount     DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '税额',
  lead_time_days INT           NULL COMMENT '交期天数',
  moq            DECIMAL(19,6) NULL COMMENT '最小起订量',
  qty_available  DECIMAL(19,6) NULL COMMENT '可供数量',
  incoterm       VARCHAR(16)   NULL COMMENT '贸易术语',
  remark         VARCHAR(255)  NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_line (quote_id, rfq_line_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商报价行';

CREATE TABLE sourcing_award (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  award_no       VARCHAR(40)   NOT NULL COMMENT '定标单号',
  rfq_id         BIGINT        NOT NULL COMMENT 'RFQ',
  supplier_id    BIGINT        NOT NULL COMMENT '供应商',
  quote_id       BIGINT        NOT NULL COMMENT '报价',
  award_amount   DECIMAL(19,4) NOT NULL COMMENT '定标金额',
  currency       CHAR(3)       NOT NULL COMMENT '币种',
  status         VARCHAR(24)   NOT NULL DEFAULT 'DRAFT'
                 COMMENT 'DRAFT/APPROVED/PO_CREATED/CONTRACT_CREATED/CANCELED',
  reason         VARCHAR(255)  NULL COMMENT '定标理由（非最低价必填）',
  approved_by    BIGINT        NULL COMMENT '审批人',
  approved_at    DATETIME(3)   NULL COMMENT '审批时间',
  version        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by     BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_award_no (award_no),
  KEY idx_award_rfq (rfq_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '寻源定标';

CREATE TABLE sourcing_award_line (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  award_id       BIGINT        NOT NULL COMMENT '定标',
  rfq_line_id    BIGINT        NOT NULL COMMENT 'RFQ 行',
  quote_line_id  BIGINT        NOT NULL COMMENT '报价行',
  awarded_qty    DECIMAL(19,6) NOT NULL COMMENT '定标数量',
  unit_price     DECIMAL(19,4) NOT NULL COMMENT '定标单价',
  amount         DECIMAL(19,4) NOT NULL COMMENT '行金额',
  PRIMARY KEY (id),
  UNIQUE KEY uk_award_line (award_id, rfq_line_id),
  KEY idx_award_line_quote (quote_line_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '定标行';

ALTER TABLE purchase_order
  ADD COLUMN source_rfq_id BIGINT NULL COMMENT '来源 RFQ',
  ADD COLUMN source_award_id BIGINT NULL COMMENT '来源定标',
  ADD COLUMN contract_id BIGINT NULL COMMENT '释放来源合同';

ALTER TABLE purchase_order_line
  ADD COLUMN award_line_id BIGINT NULL COMMENT '来源定标行',
  ADD COLUMN contract_line_id BIGINT NULL COMMENT '来源合同行',
  ADD COLUMN price_source VARCHAR(16) NULL COMMENT 'CONTRACT/AWARD/SUPPLIER_PRICE/PRICE_LIST/MANUAL';

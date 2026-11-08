-- W4：供应商发票、三单匹配容差、付款申请（docs/erp/32、34、50）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- 三单匹配 = PO 价格/数量 ↔ GR 实收 ↔ 发票；超容差落 EXCEPTION，批准需原因与更高审查。

CREATE TABLE p2p_tolerance (
  id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id BIGINT        NULL COMMENT '公司；NULL 表示全局',
  dimension  VARCHAR(16)   NOT NULL COMMENT 'PRICE/QTY',
  percent    DECIMAL(9,6)  NOT NULL COMMENT '百分比容差（0.01 = 1%）',
  absolute   DECIMAL(19,4) NOT NULL COMMENT '绝对容差下限',
  status     VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
             ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_tolerance_lookup (dimension, status, company_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '三单匹配容差';

INSERT INTO p2p_tolerance (company_id, dimension, percent, absolute, status)
VALUES
  (NULL, 'PRICE', 0.010000, 0.0100, 'ACTIVE'),
  (NULL, 'QTY', 0.020000, 1.0000, 'ACTIVE');

CREATE TABLE supplier_invoice (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_no             VARCHAR(40)   NOT NULL COMMENT '内部发票单号',
  supplier_invoice_no VARCHAR(64)  NOT NULL COMMENT '供应商发票号（同供应商唯一）',
  company_id         BIGINT        NOT NULL COMMENT '公司',
  supplier_id        BIGINT        NOT NULL COMMENT '供应商',
  po_id              BIGINT        NOT NULL COMMENT '采购订单',
  invoice_date       DATE          NOT NULL COMMENT '发票日期',
  due_date           DATE          NULL COMMENT '到期日',
  currency           CHAR(3)       NOT NULL COMMENT '币种',
  net_amount         DECIMAL(19,4) NOT NULL COMMENT '不含税金额',
  tax_amount         DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '税额',
  total_amount       DECIMAL(19,4) NOT NULL COMMENT '价税合计',
  paid_amount        DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '已付金额',
  status             VARCHAR(24)   NOT NULL DEFAULT 'RECEIVED'
                     COMMENT 'RECEIVED/MATCHED/EXCEPTION/APPROVED/PARTIALLY_PAID/PAID/CANCELED',
  created_by         BIGINT        NOT NULL COMMENT '录入人',
  approved_by        BIGINT        NULL COMMENT '审批人',
  approved_at        DATETIME(3)   NULL COMMENT '审批时间',
  version            INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_invoice_doc_no (doc_no),
  UNIQUE KEY uk_invoice_supplier_no (supplier_id, supplier_invoice_no),
  KEY idx_invoice_company_status (company_id, status, id),
  KEY idx_invoice_po (po_id),
  KEY idx_invoice_no (supplier_invoice_no),
  CONSTRAINT ck_invoice_amounts CHECK (net_amount >= 0 AND tax_amount >= 0 AND total_amount >= 0),
  CONSTRAINT ck_invoice_paid CHECK (paid_amount >= 0 AND paid_amount <= total_amount)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商发票';

CREATE TABLE supplier_invoice_line (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  invoice_id  BIGINT        NOT NULL COMMENT '发票',
  line_no     INT           NOT NULL COMMENT '行号',
  po_line_id  BIGINT        NOT NULL COMMENT '采购订单行',
  item_id     BIGINT        NOT NULL COMMENT '物料',
  qty         DECIMAL(19,6) NOT NULL COMMENT '开票数量',
  unit_price  DECIMAL(19,4) NOT NULL COMMENT '开票单价',
  amount      DECIMAL(19,4) NOT NULL COMMENT '行金额（不含税）',
  tax_amount  DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '行税额',
  PRIMARY KEY (id),
  UNIQUE KEY uk_invoice_line (invoice_id, line_no),
  KEY idx_invoice_line_po_line (po_line_id),
  CONSTRAINT ck_invoice_line_qty CHECK (qty > 0),
  CONSTRAINT ck_invoice_line_price CHECK (unit_price >= 0),
  CONSTRAINT ck_invoice_line_amount CHECK (amount >= 0 AND tax_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商发票行';

CREATE TABLE p2p_match_result (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  invoice_id      BIGINT        NOT NULL COMMENT '发票（1:1）',
  status          VARCHAR(16)   NOT NULL COMMENT 'MATCHED/EXCEPTION',
  price_variance  DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '最大单价差异',
  qty_variance    DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '最大数量差异',
  amount_variance DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '最大金额差异',
  detail          VARCHAR(1000) NULL COMMENT '差异定位摘要（不含业务敏感内容）',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_match_invoice (invoice_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '三单匹配结果';

CREATE TABLE payment_request (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_no       VARCHAR(40)   NOT NULL COMMENT '付款申请单号',
  company_id   BIGINT        NOT NULL COMMENT '公司',
  supplier_id  BIGINT        NOT NULL COMMENT '供应商',
  invoice_id   BIGINT        NOT NULL COMMENT '供应商发票',
  amount       DECIMAL(19,4) NOT NULL COMMENT '付款金额',
  currency     CHAR(3)       NOT NULL COMMENT '币种',
  status       VARCHAR(16)   NOT NULL DEFAULT 'REQUESTED'
               COMMENT 'REQUESTED/APPROVED/PAID/CANCELED',
  requested_by BIGINT        NOT NULL COMMENT '申请人',
  approved_by  BIGINT        NULL COMMENT '审批人',
  approved_at  DATETIME(3)   NULL COMMENT '审批时间',
  paid_at      DATETIME(3)   NULL COMMENT '支付登记时间',
  version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
               ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_doc_no (doc_no),
  KEY idx_payment_company_status (company_id, status, id),
  KEY idx_payment_invoice (invoice_id),
  CONSTRAINT ck_payment_amount CHECK (amount > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '付款申请';

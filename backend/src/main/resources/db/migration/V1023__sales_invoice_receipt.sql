-- E3 销售发票、客户收款与 AR（docs/erp/35 §5）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

CREATE TABLE sales_invoice (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  invoice_no   VARCHAR(40)   NOT NULL COMMENT '发票号',
  company_id   BIGINT        NOT NULL COMMENT '公司',
  customer_id  BIGINT        NOT NULL COMMENT '客户',
  order_id     BIGINT        NOT NULL COMMENT '销售订单',
  currency     CHAR(3)       NOT NULL COMMENT '币种',
  status       VARCHAR(16)   NOT NULL DEFAULT 'ISSUED'
               COMMENT 'ISSUED/PARTIAL/PAID/VOID',
  net_amount   DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '未税金额',
  tax_amount   DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '税额',
  total_amount DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '含税金额',
  paid_amount  DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '已收金额',
  issued_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开票时间',
  due_date     DATE          NOT NULL COMMENT '到期日',
  remark       VARCHAR(255)  NULL COMMENT '备注',
  version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by   BIGINT        NOT NULL DEFAULT 0 COMMENT '开票人',
  created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_invoice_no (invoice_no),
  KEY idx_sales_invoice_customer (company_id, customer_id, status, due_date),
  KEY idx_sales_invoice_order (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售发票';

CREATE TABLE sales_invoice_line (
  id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  invoice_id        BIGINT        NOT NULL COMMENT '发票',
  line_no           INT           NOT NULL COMMENT '行号',
  order_line_id     BIGINT        NOT NULL COMMENT '销售订单行',
  shipment_line_id  BIGINT        NULL COMMENT '发货行',
  item_id           BIGINT        NOT NULL COMMENT '物料',
  qty               DECIMAL(19,6) NOT NULL COMMENT '数量',
  unit_price        DECIMAL(19,4) NOT NULL COMMENT '未税单价',
  discount_rate     DECIMAL(9,6)  NOT NULL DEFAULT 0 COMMENT '折扣率',
  tax_rate          DECIMAL(9,6)  NOT NULL DEFAULT 0 COMMENT '税率',
  net_amount        DECIMAL(19,4) NOT NULL COMMENT '未税金额',
  tax_amount        DECIMAL(19,4) NOT NULL COMMENT '税额',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_invoice_line (invoice_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售发票行';

CREATE TABLE customer_receipt (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  receipt_no   VARCHAR(40)   NOT NULL COMMENT '收款单号',
  company_id   BIGINT        NOT NULL COMMENT '公司',
  customer_id  BIGINT        NOT NULL COMMENT '客户',
  currency     CHAR(3)       NOT NULL COMMENT '币种',
  amount       DECIMAL(19,4) NOT NULL COMMENT '收款金额',
  method       VARCHAR(32)   NOT NULL DEFAULT 'BANK' COMMENT '收款方式',
  status       VARCHAR(16)   NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED/CLEARED/VOID',
  received_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '收款时间',
  version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by   BIGINT        NOT NULL DEFAULT 0 COMMENT '登记人',
  created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_customer_receipt_no (receipt_no),
  KEY idx_customer_receipt_customer (company_id, customer_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '客户收款';

CREATE TABLE receipt_allocation (
  id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  receipt_id BIGINT        NOT NULL COMMENT '收款',
  invoice_id BIGINT        NOT NULL COMMENT '发票',
  amount     DECIMAL(19,4) NOT NULL COMMENT '核销金额',
  PRIMARY KEY (id),
  UNIQUE KEY uk_receipt_allocation (receipt_id, invoice_id),
  KEY idx_receipt_allocation_invoice (invoice_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '收款核销';

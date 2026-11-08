-- E3 销售到收款：报价与销售订单（docs/erp/35 §5）。
-- 只增不改：应用后本文件冻结（06 §1.3）。发货/发票/收款在后续迁移。

CREATE TABLE sales_quote (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  quote_no           VARCHAR(40)   NOT NULL COMMENT '报价单号',
  company_id         BIGINT        NOT NULL COMMENT '公司',
  customer_id        BIGINT        NOT NULL COMMENT '客户',
  currency           CHAR(3)       NOT NULL COMMENT '币种',
  valid_until        DATE          NOT NULL COMMENT '有效期至',
  status             VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'
                     COMMENT 'DRAFT/SENT/ACCEPTED/EXPIRED',
  net_amount         DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '未税金额',
  tax_amount         DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '税额',
  total_amount       DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '含税金额',
  version            INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by         BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  sent_at            DATETIME(3)   NULL COMMENT '发送时间',
  accepted_at        DATETIME(3)   NULL COMMENT '接受时间',
  converted_order_id BIGINT        NULL COMMENT '转出的销售订单',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_quote_no (quote_no),
  KEY idx_sales_quote_customer (company_id, customer_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售报价';

CREATE TABLE sales_quote_line (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  quote_id      BIGINT        NOT NULL COMMENT '报价',
  line_no       INT           NOT NULL COMMENT '行号',
  item_id       BIGINT        NOT NULL COMMENT '物料',
  qty           DECIMAL(19,6) NOT NULL COMMENT '数量',
  uom           VARCHAR(16)   NOT NULL COMMENT '单位',
  unit_price    DECIMAL(19,4) NOT NULL COMMENT '未税单价',
  discount_rate DECIMAL(9,6)  NOT NULL DEFAULT 0 COMMENT '折扣率',
  tax_rate      DECIMAL(9,6)  NOT NULL DEFAULT 0 COMMENT '税率',
  net_amount    DECIMAL(19,4) NOT NULL COMMENT '未税金额',
  tax_amount    DECIMAL(19,4) NOT NULL COMMENT '税额',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_quote_line (quote_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售报价行';

CREATE TABLE sales_order (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_no        VARCHAR(40)   NOT NULL COMMENT '订单号',
  company_id      BIGINT        NOT NULL COMMENT '公司',
  customer_id     BIGINT        NOT NULL COMMENT '客户',
  source_quote_id BIGINT        NULL COMMENT '来源报价',
  currency        CHAR(3)       NOT NULL COMMENT '币种',
  payment_terms   VARCHAR(32)   NULL COMMENT '付款条件',
  status          VARCHAR(24)   NOT NULL DEFAULT 'DRAFT'
                  COMMENT 'DRAFT/CONFIRMED/PARTIAL_SHIPPED/SHIPPED/CLOSED/CANCELED',
  net_amount      DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '未税金额',
  tax_amount      DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '税额',
  total_amount    DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '含税金额',
  version         INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by      BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  confirmed_by    BIGINT        NULL COMMENT '确认人',
  confirmed_at    DATETIME(3)   NULL COMMENT '确认时间',
  canceled_at     DATETIME(3)   NULL COMMENT '取消时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_order_no (order_no),
  KEY idx_sales_order_customer (company_id, customer_id, status),
  KEY idx_sales_order_quote (source_quote_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售订单';

CREATE TABLE sales_order_line (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id      BIGINT        NOT NULL COMMENT '销售订单',
  line_no       INT           NOT NULL COMMENT '行号',
  item_id       BIGINT        NOT NULL COMMENT '物料',
  qty           DECIMAL(19,6) NOT NULL COMMENT '数量',
  uom           VARCHAR(16)   NOT NULL COMMENT '单位',
  unit_price    DECIMAL(19,4) NOT NULL COMMENT '未税单价',
  discount_rate DECIMAL(9,6)  NOT NULL DEFAULT 0 COMMENT '折扣率',
  tax_rate      DECIMAL(9,6)  NOT NULL DEFAULT 0 COMMENT '税率',
  net_amount    DECIMAL(19,4) NOT NULL COMMENT '未税金额',
  tax_amount    DECIMAL(19,4) NOT NULL COMMENT '税额',
  shipped_qty   DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '已发货数量',
  invoiced_qty  DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '已开票数量',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_order_line (order_id, line_no),
  KEY idx_sales_order_line_item (item_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售订单行';

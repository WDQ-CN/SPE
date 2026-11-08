-- E3 销售成本与退货/贷项（docs/erp/35 §4/§5）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

ALTER TABLE sales_shipment_line
  ADD COLUMN cost_unit DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '出库单位成本',
  ADD COLUMN cost_amount DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '出库成本金额',
  ADD COLUMN returned_qty DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '已退货数量';

CREATE TABLE sales_return (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  return_no    VARCHAR(40)   NOT NULL COMMENT '退货单号',
  company_id   BIGINT        NOT NULL COMMENT '公司',
  customer_id  BIGINT        NOT NULL COMMENT '客户',
  shipment_id  BIGINT        NOT NULL COMMENT '发货单',
  order_id     BIGINT        NOT NULL COMMENT '销售订单',
  currency     CHAR(3)       NOT NULL COMMENT '币种',
  status       VARCHAR(16)   NOT NULL DEFAULT 'REQUESTED'
               COMMENT 'REQUESTED/CREDITED/CANCELED',
  net_amount   DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '未税金额',
  tax_amount   DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '税额',
  total_amount DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '含税金额',
  reason       VARCHAR(255)  NULL COMMENT '退货原因',
  credited_at  DATETIME(3)   NULL COMMENT '贷项时间',
  version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by   BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_return_no (return_no),
  KEY idx_sales_return_shipment (shipment_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售退货/贷项';

CREATE TABLE sales_return_line (
  id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  return_id         BIGINT        NOT NULL COMMENT '退货单',
  line_no           INT           NOT NULL COMMENT '行号',
  shipment_line_id  BIGINT        NOT NULL COMMENT '发货行',
  order_line_id     BIGINT        NOT NULL COMMENT '订单行',
  item_id           BIGINT        NOT NULL COMMENT '物料',
  qty               DECIMAL(19,6) NOT NULL COMMENT '退货数量',
  unit_price        DECIMAL(19,4) NOT NULL COMMENT '未税单价',
  discount_rate     DECIMAL(9,6)  NOT NULL DEFAULT 0 COMMENT '折扣率',
  tax_rate          DECIMAL(9,6)  NOT NULL DEFAULT 0 COMMENT '税率',
  net_amount        DECIMAL(19,4) NOT NULL COMMENT '未税金额',
  tax_amount        DECIMAL(19,4) NOT NULL COMMENT '税额',
  reason            VARCHAR(255)  NULL COMMENT '行原因',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_return_line (return_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售退货行';

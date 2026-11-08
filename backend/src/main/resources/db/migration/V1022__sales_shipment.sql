-- E3 销售发货（docs/erp/35 §5）。发货过账同时通过 inventory.api 出库。
-- 只增不改：应用后本文件冻结（06 §1.3）。

CREATE TABLE sales_shipment (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  shipment_no  VARCHAR(40) NOT NULL COMMENT '发货单号',
  company_id   BIGINT      NOT NULL COMMENT '公司',
  customer_id  BIGINT      NOT NULL COMMENT '客户',
  order_id     BIGINT      NOT NULL COMMENT '销售订单',
  warehouse_id BIGINT      NOT NULL COMMENT '发货仓库',
  status       VARCHAR(16) NOT NULL DEFAULT 'SHIPPED'
               COMMENT 'SHIPPED/DELIVERED/CANCELED',
  shipped_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发货时间',
  created_by   BIGINT      NOT NULL DEFAULT 0 COMMENT '发货人',
  version      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_shipment_no (shipment_no),
  KEY idx_sales_shipment_order (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售发货单';

CREATE TABLE sales_shipment_line (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  shipment_id   BIGINT        NOT NULL COMMENT '发货单',
  line_no       INT           NOT NULL COMMENT '行号',
  order_line_id BIGINT        NOT NULL COMMENT '销售订单行',
  item_id       BIGINT        NOT NULL COMMENT '物料',
  lot_no        VARCHAR(64)   NULL COMMENT '批次号',
  location_id   BIGINT        NOT NULL DEFAULT 0 COMMENT '库位；0=未分配',
  qty           DECIMAL(19,6) NOT NULL COMMENT '发货数量',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sales_shipment_line (shipment_id, line_no),
  KEY idx_sales_shipment_line_order (order_line_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '销售发货行';

ALTER TABLE sales_order_line
  ADD KEY idx_sales_order_line_order_qty (order_id, qty);

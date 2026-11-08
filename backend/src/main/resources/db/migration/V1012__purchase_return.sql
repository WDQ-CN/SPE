-- W3 续：采购退货与 AP 贷项（docs/erp/32 §5、34 §5.1、52）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- 退货减少库存与 PO 可开票量；已开票退货借记 AP，未开票退货借记 GR/IR。

ALTER TABLE purchase_order_line
  ADD COLUMN returned_qty DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '已退货数量';

CREATE TABLE purchase_return (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_no       VARCHAR(40)   NOT NULL COMMENT '退货单号',
  company_id   BIGINT        NOT NULL COMMENT '公司',
  supplier_id  BIGINT        NOT NULL COMMENT '供应商',
  po_id        BIGINT        NOT NULL COMMENT '采购订单',
  warehouse_id BIGINT        NOT NULL COMMENT '退货仓库',
  return_date  DATE          NOT NULL COMMENT '退货日期',
  currency     CHAR(3)       NOT NULL COMMENT '币种',
  net_amount   DECIMAL(19,4) NOT NULL COMMENT '退货净额',
  invoiced     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否已开票（决定 AP/GR-IR）',
  status       VARCHAR(16)   NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED/CANCELED',
  reason       VARCHAR(200)  NOT NULL COMMENT '退货原因',
  created_by   BIGINT        NOT NULL COMMENT '创建人',
  version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_purchase_return_doc_no (doc_no),
  KEY idx_return_company_date (company_id, return_date, id),
  KEY idx_return_po (po_id),
  CONSTRAINT ck_return_amount CHECK (net_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '采购退货';

CREATE TABLE purchase_return_line (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  return_id   BIGINT        NOT NULL COMMENT '退货单',
  line_no     INT           NOT NULL COMMENT '行号',
  po_line_id  BIGINT        NOT NULL COMMENT '采购订单行',
  item_id     BIGINT        NOT NULL COMMENT '物料',
  qty         DECIMAL(19,6) NOT NULL COMMENT '退货数量',
  uom         VARCHAR(16)   NOT NULL COMMENT '单位',
  unit_cost   DECIMAL(19,4) NOT NULL COMMENT '退货成本（PO 单价）',
  amount      DECIMAL(19,4) NOT NULL COMMENT '退货金额',
  PRIMARY KEY (id),
  UNIQUE KEY uk_return_line (return_id, line_no),
  KEY idx_return_line_po_line (po_line_id),
  CONSTRAINT ck_return_line_qty CHECK (qty > 0),
  CONSTRAINT ck_return_line_cost CHECK (unit_cost >= 0 AND amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '采购退货行';

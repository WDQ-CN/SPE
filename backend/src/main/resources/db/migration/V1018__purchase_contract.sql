-- E2 合同/框架协议与供应商价格库（docs/erp/55 §3.5）。
-- 只增不改：应用后本文件冻结（06 §1.3）。PO 的 contract_id 已在 V1017。

CREATE TABLE purchase_contract (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_no        VARCHAR(40)   NOT NULL COMMENT '合同号',
  company_id         BIGINT        NOT NULL COMMENT '公司',
  supplier_id        BIGINT        NOT NULL COMMENT '供应商',
  title              VARCHAR(200)  NOT NULL COMMENT '标题',
  currency           CHAR(3)       NOT NULL COMMENT '币种',
  valid_from         DATE          NOT NULL COMMENT '生效日',
  valid_to           DATE          NOT NULL COMMENT '失效日',
  payment_terms      VARCHAR(32)   NULL COMMENT '付款条件',
  status             VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'
                     COMMENT 'DRAFT/ACTIVE/EXPIRED/TERMINATED',
  total_limit_amount DECIMAL(19,4) NULL COMMENT '金额上限',
  total_limit_qty    DECIMAL(19,6) NULL COMMENT '数量上限',
  source_award_id    BIGINT        NULL COMMENT '来源定标',
  version            INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by         BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  approved_by        BIGINT        NULL COMMENT '审批人',
  approved_at        DATETIME(3)   NULL COMMENT '审批时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_contract_no (contract_no),
  KEY idx_contract_supplier (company_id, supplier_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '采购合同/框架协议';

CREATE TABLE purchase_contract_line (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_id  BIGINT        NOT NULL COMMENT '合同',
  line_no      INT           NOT NULL COMMENT '行号',
  item_id      BIGINT        NOT NULL COMMENT '物料',
  uom          VARCHAR(16)   NOT NULL COMMENT '单位',
  price        DECIMAL(19,4) NOT NULL COMMENT '合同单价',
  min_qty      DECIMAL(19,6) NULL COMMENT '最小释放量',
  max_qty      DECIMAL(19,6) NULL COMMENT '最大释放量',
  tax_included TINYINT       NOT NULL DEFAULT 0 COMMENT '价格是否含税',
  valid_from   DATE          NULL COMMENT '行生效日',
  valid_to     DATE          NULL COMMENT '行失效日',
  PRIMARY KEY (id),
  UNIQUE KEY uk_contract_line (contract_id, line_no),
  KEY idx_contract_line_item (item_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '合同行';

CREATE TABLE contract_consumption (
  id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_line_id BIGINT        NOT NULL COMMENT '合同行',
  po_id            BIGINT        NOT NULL COMMENT '采购订单',
  po_line_id       BIGINT        NOT NULL COMMENT '采购订单行',
  qty              DECIMAL(19,6) NOT NULL COMMENT '释放数量',
  amount           DECIMAL(19,4) NOT NULL COMMENT '释放金额',
  consumed_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '释放时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_consumption_po_line (po_line_id),
  KEY idx_consumption_contract_line (contract_line_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '合同消耗';

CREATE TABLE supplier_price (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id   BIGINT        NOT NULL COMMENT '公司',
  supplier_id  BIGINT        NOT NULL COMMENT '供应商',
  item_id      BIGINT        NOT NULL COMMENT '物料',
  uom          VARCHAR(16)   NOT NULL COMMENT '单位',
  min_qty      DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '最小数量档',
  price        DECIMAL(19,4) NOT NULL COMMENT '价格',
  tax_included TINYINT       NOT NULL DEFAULT 0 COMMENT '价格是否含税',
  currency     CHAR(3)       NOT NULL COMMENT '币种',
  valid_from   DATE          NOT NULL COMMENT '生效日',
  valid_to     DATE          NULL COMMENT '失效日',
  source_type  VARCHAR(16)   NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/CONTRACT/AWARD',
  contract_id  BIGINT        NULL COMMENT '来源合同',
  award_id     BIGINT        NULL COMMENT '来源定标',
  status       VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_price
    (company_id, supplier_id, item_id, uom, min_qty, valid_from),
  KEY idx_supplier_price_lookup (supplier_id, item_id, status, valid_from)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商价格库';

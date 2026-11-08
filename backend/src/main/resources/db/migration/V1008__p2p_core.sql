-- W2：P2P 核心（docs/erp/32、50、52）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- PR → PO → 审批矩阵 → 收货；库存台账/估值与三单匹配/付款在 W3/W4 追加。

CREATE TABLE p2p_approval_rule (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id    BIGINT        NULL COMMENT '公司；NULL 表示全局规则',
  doc_type      VARCHAR(16)   NOT NULL COMMENT 'PR/PO',
  min_amount    DECIMAL(19,4) NOT NULL COMMENT '金额下限（含）',
  max_amount    DECIMAL(19,4) NULL COMMENT '金额上限（含）；NULL 表示无上限',
  required_role VARCHAR(16)   NOT NULL COMMENT 'ADMIN/MANAGER',
  priority      INT           NOT NULL DEFAULT 10 COMMENT '越小越优先',
  status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_p2p_rule_lookup (doc_type, status, company_id, priority)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'P2P 审批矩阵';

INSERT INTO p2p_approval_rule
  (company_id, doc_type, min_amount, max_amount, required_role, priority, status)
VALUES
  (NULL, 'PR', 0, 50000, 'MANAGER', 10, 'ACTIVE'),
  (NULL, 'PR', 50000, NULL, 'ADMIN', 20, 'ACTIVE'),
  (NULL, 'PO', 0, 500000, 'MANAGER', 10, 'ACTIVE'),
  (NULL, 'PO', 500000, NULL, 'ADMIN', 20, 'ACTIVE');

CREATE TABLE purchase_requisition (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_no       VARCHAR(40)   NOT NULL COMMENT '请购单号',
  company_id   BIGINT        NOT NULL COMMENT '公司',
  requester_id BIGINT        NOT NULL COMMENT '申请人',
  need_date    DATE          NOT NULL COMMENT '需求日期',
  currency     CHAR(3)       NOT NULL COMMENT '币种',
  amount       DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '预估金额',
  status       VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'
               COMMENT 'DRAFT/SUBMITTED/APPROVED/REJECTED/CONVERTED/CLOSED',
  version      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
               ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pr_doc_no (doc_no),
  KEY idx_pr_company_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '请购单';

CREATE TABLE purchase_requisition_line (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  requisition_id  BIGINT        NOT NULL COMMENT '请购单',
  line_no         INT           NOT NULL COMMENT '行号',
  item_id         BIGINT        NOT NULL COMMENT '物料',
  qty             DECIMAL(19,6) NOT NULL COMMENT '数量',
  uom             VARCHAR(16)   NOT NULL COMMENT '单位',
  estimated_price DECIMAL(19,4) NOT NULL COMMENT '预估单价',
  amount          DECIMAL(19,4) NOT NULL COMMENT '行金额',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pr_line (requisition_id, line_no),
  KEY idx_pr_line_item (item_id),
  CONSTRAINT ck_pr_line_qty CHECK (qty > 0),
  CONSTRAINT ck_pr_line_price CHECK (estimated_price >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '请购单行';

CREATE TABLE purchase_order (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_no         VARCHAR(40)   NOT NULL COMMENT '采购订单号',
  company_id     BIGINT        NOT NULL COMMENT '公司',
  supplier_id    BIGINT        NOT NULL COMMENT '供应商',
  plant_id       BIGINT        NOT NULL COMMENT '工厂',
  source_pr_id   BIGINT        NULL COMMENT '来源请购单',
  currency       CHAR(3)       NOT NULL COMMENT '币种',
  payment_terms  VARCHAR(32)   NULL COMMENT '付款条件',
  amount         DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '订单金额',
  status         VARCHAR(24)   NOT NULL DEFAULT 'DRAFT'
                 COMMENT 'DRAFT/APPROVED/REJECTED/SENT/PARTIAL_RECEIVED/RECEIVED/CLOSED/CANCELED',
  created_by     BIGINT        NOT NULL COMMENT '创建人',
  approved_by    BIGINT        NULL COMMENT '审批人',
  approved_at    DATETIME(3)   NULL COMMENT '审批时间',
  version        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                 ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_po_doc_no (doc_no),
  KEY idx_po_company_status (company_id, status, id),
  KEY idx_po_supplier (supplier_id),
  KEY idx_po_source_pr (source_pr_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '采购订单';

CREATE TABLE purchase_order_line (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  po_id         BIGINT        NOT NULL COMMENT '采购订单',
  line_no       INT           NOT NULL COMMENT '行号',
  item_id       BIGINT        NOT NULL COMMENT '物料',
  qty           DECIMAL(19,6) NOT NULL COMMENT '订购数量',
  uom           VARCHAR(16)   NOT NULL COMMENT '单位',
  price         DECIMAL(19,4) NOT NULL COMMENT '单价',
  amount        DECIMAL(19,4) NOT NULL COMMENT '行金额',
  received_qty  DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '已收货数量',
  invoiced_qty  DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '已开票数量',
  PRIMARY KEY (id),
  UNIQUE KEY uk_po_line (po_id, line_no),
  KEY idx_po_line_item (item_id),
  CONSTRAINT ck_po_line_qty CHECK (qty > 0),
  CONSTRAINT ck_po_line_price CHECK (price >= 0),
  CONSTRAINT ck_po_line_received CHECK (received_qty >= 0),
  CONSTRAINT ck_po_line_invoiced CHECK (invoiced_qty >= 0),
  CONSTRAINT ck_po_line_received_not_over CHECK (received_qty <= qty)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '采购订单行';

CREATE TABLE goods_receipt (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_no       VARCHAR(40) NOT NULL COMMENT '收货单号',
  po_id        BIGINT      NOT NULL COMMENT '采购订单',
  company_id   BIGINT      NOT NULL COMMENT '公司',
  warehouse_id BIGINT      NOT NULL COMMENT '仓库',
  status       VARCHAR(16) NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED/REVERSED',
  created_by   BIGINT      NOT NULL COMMENT '收货人',
  received_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '收货时间',
  version      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gr_doc_no (doc_no),
  KEY idx_gr_po (po_id),
  KEY idx_gr_company_time (company_id, received_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '收货单';

CREATE TABLE goods_receipt_line (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  gr_id       BIGINT        NOT NULL COMMENT '收货单',
  line_no     INT           NOT NULL COMMENT '行号',
  po_line_id  BIGINT        NOT NULL COMMENT '采购订单行',
  item_id     BIGINT        NOT NULL COMMENT '物料',
  qty         DECIMAL(19,6) NOT NULL COMMENT '收货数量',
  uom         VARCHAR(16)   NOT NULL COMMENT '单位',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gr_line (gr_id, line_no),
  KEY idx_gr_line_po_line (po_line_id),
  CONSTRAINT ck_gr_line_qty CHECK (qty > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '收货单行';

-- E4 制造核心：BOM、工单、领料、完工入库（docs/erp/36 §5）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

CREATE TABLE bom_header (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  bom_no       VARCHAR(40)  NOT NULL COMMENT 'BOM 编号',
  company_id   BIGINT       NOT NULL COMMENT '公司',
  item_id      BIGINT       NOT NULL COMMENT '产出物料',
  version      VARCHAR(16)  NOT NULL DEFAULT 'V1' COMMENT '版本',
  status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/INACTIVE',
  created_by   BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  activated_at DATETIME(3)  NULL COMMENT '生效时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bom_no (bom_no),
  UNIQUE KEY uk_bom_item_version (item_id, version),
  KEY idx_bom_item_status (item_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'BOM 头';

CREATE TABLE bom_line (
  id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  bom_id            BIGINT        NOT NULL COMMENT 'BOM',
  line_no           INT           NOT NULL COMMENT '行号',
  component_item_id BIGINT        NOT NULL COMMENT '组件物料',
  qty               DECIMAL(19,6) NOT NULL COMMENT '单位用量',
  uom               VARCHAR(16)   NOT NULL COMMENT '单位',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bom_line (bom_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'BOM 行';

CREATE TABLE work_order (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_no      VARCHAR(40)   NOT NULL COMMENT '工单号',
  company_id    BIGINT        NOT NULL COMMENT '公司',
  plant_id      BIGINT        NOT NULL COMMENT '工厂',
  item_id       BIGINT        NOT NULL COMMENT '产出物料',
  bom_id        BIGINT        NOT NULL COMMENT '冻结 BOM',
  qty           DECIMAL(19,6) NOT NULL COMMENT '计划数量',
  status        VARCHAR(16)   NOT NULL DEFAULT 'CREATED'
                COMMENT 'CREATED/RELEASED/IN_PROGRESS/COMPLETED/CLOSED/CANCELED',
  material_cost DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '累计材料成本',
  received_qty  DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '已完工入库数量',
  version       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by    BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  released_at   DATETIME(3)   NULL COMMENT '下达时间',
  completed_at  DATETIME(3)   NULL COMMENT '完工时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_wo_no (order_no),
  KEY idx_wo_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '生产工单';

CREATE TABLE work_order_component (
  id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  work_order_id     BIGINT        NOT NULL COMMENT '工单',
  line_no           INT           NOT NULL COMMENT '行号',
  bom_line_id       BIGINT        NOT NULL COMMENT '来源 BOM 行',
  component_item_id BIGINT        NOT NULL COMMENT '组件物料',
  qty_required      DECIMAL(19,6) NOT NULL COMMENT '需求总量',
  uom               VARCHAR(16)   NOT NULL COMMENT '单位',
  issued_qty        DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '已领数量',
  PRIMARY KEY (id),
  UNIQUE KEY uk_wo_component (work_order_id, line_no),
  KEY idx_wo_component_item (component_item_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '工单组件（下达时冻结）';

CREATE TABLE production_issue (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  work_order_id   BIGINT        NOT NULL COMMENT '工单',
  component_id    BIGINT        NOT NULL COMMENT '工单组件',
  item_id         BIGINT        NOT NULL COMMENT '物料',
  lot_no          VARCHAR(64)   NULL COMMENT '批次',
  location_id     BIGINT        NOT NULL DEFAULT 0 COMMENT '库位',
  qty             DECIMAL(19,6) NOT NULL COMMENT '领料数量',
  unit_cost       DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '出库成本',
  amount          DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '出库金额',
  created_by      BIGINT        NOT NULL DEFAULT 0 COMMENT '操作人',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_production_issue_wo (work_order_id, component_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '生产领料';

CREATE TABLE production_receipt (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  receipt_no   VARCHAR(40)   NOT NULL COMMENT '完工单号',
  work_order_id BIGINT       NOT NULL COMMENT '工单',
  warehouse_id BIGINT        NOT NULL COMMENT '仓库',
  location_id  BIGINT        NOT NULL DEFAULT 0 COMMENT '库位',
  lot_no       VARCHAR(64)   NULL COMMENT '批次',
  qty          DECIMAL(19,6) NOT NULL COMMENT '完工数量',
  unit_cost    DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '单位成本',
  amount       DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '金额',
  status       VARCHAR(16)   NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED/REVERSED',
  created_by   BIGINT        NOT NULL DEFAULT 0 COMMENT '操作人',
  created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_production_receipt_no (receipt_no),
  KEY idx_production_receipt_wo (work_order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '生产完工入库';

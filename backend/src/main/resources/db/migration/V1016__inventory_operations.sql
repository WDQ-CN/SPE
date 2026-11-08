-- E2-W2/W3：预留/冻结、调拨、盘点、库位容量与 FIFO/标准成本（docs/erp/54）。
-- 只增不改：应用后本文件冻结（06 §1.3）。库存余额仍按 物料+批次+库位 聚合。

ALTER TABLE inventory_stock
  ADD COLUMN qty_reserved DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '预留量',
  ADD COLUMN qty_frozen   DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '冻结量';

ALTER TABLE item
  ADD COLUMN standard_cost DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '标准成本';

ALTER TABLE account_mapping
  ADD COLUMN variance_account_id BIGINT NULL COMMENT '标准成本采购价差科目';

ALTER TABLE warehouse_location
  ADD COLUMN max_volume DECIMAL(19,6) NULL COMMENT '库位最大体积',
  ADD COLUMN max_weight DECIMAL(19,6) NULL COMMENT '库位最大重量';

CREATE TABLE inventory_reservation (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id     BIGINT        NOT NULL COMMENT '公司',
  warehouse_id   BIGINT        NOT NULL COMMENT '仓库',
  location_id    BIGINT        NOT NULL DEFAULT 0 COMMENT '库位；0=未分配',
  item_id        BIGINT        NOT NULL COMMENT '物料',
  lot_id         BIGINT        NOT NULL DEFAULT 0 COMMENT '批次；0=非批次',
  serial_id      BIGINT        NOT NULL DEFAULT 0 COMMENT '序列；0=非序列',
  qty            DECIMAL(19,6) NOT NULL COMMENT '预留数量',
  source_type    VARCHAR(32)   NOT NULL COMMENT '来源类型',
  source_id      BIGINT        NOT NULL COMMENT '来源单据',
  source_line_id BIGINT        NOT NULL DEFAULT 0 COMMENT '来源行',
  status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RELEASED/CONSUMED/EXPIRED',
  expires_at     DATETIME(3)   NULL COMMENT '过期时间',
  created_by     BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  version        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                 ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_reservation_source
    (source_type, source_id, source_line_id, lot_id, location_id, serial_id),
  KEY idx_reservation_active (warehouse_id, item_id, status),
  KEY idx_reservation_expiry (status, expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存预留';

CREATE TABLE inventory_freeze (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id     BIGINT        NOT NULL COMMENT '公司',
  warehouse_id   BIGINT        NOT NULL COMMENT '仓库',
  location_id    BIGINT        NOT NULL DEFAULT 0 COMMENT '库位；0=未分配',
  item_id        BIGINT        NOT NULL COMMENT '物料',
  lot_id         BIGINT        NOT NULL DEFAULT 0 COMMENT '批次；0=非批次',
  serial_id      BIGINT        NOT NULL DEFAULT 0 COMMENT '序列；0=非序列',
  qty            DECIMAL(19,6) NOT NULL COMMENT '冻结数量',
  reason         VARCHAR(255)  NOT NULL COMMENT '冻结原因',
  source_type    VARCHAR(32)   NOT NULL COMMENT '来源类型',
  source_id      BIGINT        NOT NULL COMMENT '来源单据',
  status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RELEASED',
  created_by     BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  version        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                 ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_freeze_source (source_type, source_id, lot_id, location_id, serial_id),
  KEY idx_freeze_active (warehouse_id, item_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存冻结';

CREATE TABLE inventory_transfer (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  transfer_no       VARCHAR(40)  NOT NULL COMMENT '调拨单号',
  company_id        BIGINT       NOT NULL COMMENT '公司',
  from_plant_id     BIGINT       NOT NULL COMMENT '源工厂',
  from_warehouse_id BIGINT       NOT NULL COMMENT '源仓库',
  from_location_id  BIGINT       NOT NULL DEFAULT 0 COMMENT '源库位；0=未分配',
  to_plant_id       BIGINT       NOT NULL COMMENT '目标工厂',
  to_warehouse_id   BIGINT       NOT NULL COMMENT '目标仓库',
  to_location_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '目标库位；0=未分配',
  status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                    COMMENT 'DRAFT/IN_TRANSIT/RECEIVED/CANCELED',
  reason            VARCHAR(255) NULL COMMENT '调拨原因',
  requested_by      BIGINT       NOT NULL DEFAULT 0 COMMENT '申请人',
  approved_by       BIGINT       NULL COMMENT '审批人',
  approved_at       DATETIME(3)  NULL COMMENT '审批时间',
  shipped_at        DATETIME(3)  NULL COMMENT '发运时间',
  received_at       DATETIME(3)  NULL COMMENT '接收时间',
  version           INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_transfer_no (transfer_no),
  KEY idx_transfer_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存调拨';

CREATE TABLE inventory_transfer_line (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  transfer_id BIGINT        NOT NULL COMMENT '调拨单',
  line_no     INT           NOT NULL COMMENT '行号',
  item_id     BIGINT        NOT NULL COMMENT '物料',
  lot_id      BIGINT        NOT NULL DEFAULT 0 COMMENT '批次；0=非批次',
  qty         DECIMAL(19,6) NOT NULL COMMENT '数量',
  uom         VARCHAR(16)   NOT NULL COMMENT '单位',
  unit_cost   DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '单位成本',
  amount      DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '金额',
  PRIMARY KEY (id),
  UNIQUE KEY uk_transfer_line (transfer_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '调拨行';

CREATE TABLE inventory_transfer_line_serial (
  transfer_line_id BIGINT NOT NULL COMMENT '调拨行',
  serial_id        BIGINT NOT NULL COMMENT '序列',
  PRIMARY KEY (transfer_line_id, serial_id),
  KEY idx_transfer_serial (serial_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '调拨行序列';

CREATE TABLE inventory_count (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  count_no    VARCHAR(40)  NOT NULL COMMENT '盘点单号',
  company_id  BIGINT       NOT NULL COMMENT '公司',
  warehouse_id BIGINT      NOT NULL COMMENT '仓库',
  status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
              COMMENT 'DRAFT/COUNTING/REVIEW/APPROVED/POSTED/CANCELED',
  snapshot_at DATETIME(3)  NULL COMMENT '快照时间',
  counted_by  BIGINT       NULL COMMENT '盘点人',
  reviewed_by BIGINT       NULL COMMENT '复核人',
  reviewed_at DATETIME(3)  NULL COMMENT '复核时间',
  posted_by   BIGINT       NULL COMMENT '过账人',
  posted_at   DATETIME(3)  NULL COMMENT '过账时间',
  reason      VARCHAR(255) NULL COMMENT '盘点原因',
  version     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_count_no (count_no),
  KEY idx_count_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存盘点';

CREATE TABLE inventory_count_line (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  count_id     BIGINT        NOT NULL COMMENT '盘点单',
  line_no      INT           NOT NULL COMMENT '行号',
  item_id      BIGINT        NOT NULL COMMENT '物料',
  lot_id       BIGINT        NOT NULL DEFAULT 0 COMMENT '批次；0=非批次',
  location_id  BIGINT        NOT NULL DEFAULT 0 COMMENT '库位；0=未分配',
  snapshot_qty DECIMAL(19,6) NOT NULL COMMENT '快照数量',
  counted_qty  DECIMAL(19,6) NULL COMMENT '实盘数量',
  variance_qty DECIMAL(19,6) NULL COMMENT '差异数量',
  reason       VARCHAR(255)  NULL COMMENT '差异原因',
  PRIMARY KEY (id),
  UNIQUE KEY uk_count_line (count_id, line_no),
  KEY idx_count_line_item (count_id, item_id, lot_id, location_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '盘点行';

CREATE TABLE inventory_count_serial (
  count_line_id BIGINT      NOT NULL COMMENT '盘点行',
  serial_id     BIGINT      NOT NULL COMMENT '序列',
  counted_status VARCHAR(16) NULL COMMENT '实盘状态',
  PRIMARY KEY (count_line_id, serial_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '盘点序列';

CREATE TABLE location_capacity (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  location_id BIGINT        NOT NULL COMMENT '库位',
  item_id     BIGINT        NOT NULL DEFAULT 0 COMMENT '物料；0=库位总件数上限',
  max_qty     DECIMAL(19,6) NOT NULL COMMENT '最大数量',
  uom         VARCHAR(16)   NULL COMMENT '单位',
  status      VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version     INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_location_capacity (location_id, item_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库位容量';

CREATE TABLE inventory_valuation_layer (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id         BIGINT        NOT NULL COMMENT '公司',
  warehouse_id       BIGINT        NOT NULL COMMENT '仓库',
  item_id            BIGINT        NOT NULL COMMENT '物料',
  lot_id             BIGINT        NOT NULL DEFAULT 0 COMMENT '批次；0=非批次',
  method             VARCHAR(16)   NOT NULL COMMENT 'FIFO/STANDARD',
  source_movement_id BIGINT        NOT NULL COMMENT '来源库存移动',
  layer_date         DATE          NOT NULL COMMENT '建层日期',
  qty                DECIMAL(19,6) NOT NULL COMMENT '入库数量',
  remaining_qty      DECIMAL(19,6) NOT NULL COMMENT '剩余数量',
  unit_cost          DECIMAL(19,6) NOT NULL COMMENT '单位成本',
  amount             DECIMAL(19,4) NOT NULL COMMENT '入库金额',
  remaining_amount   DECIMAL(19,4) NOT NULL COMMENT '剩余金额',
  status             VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/DEPLETED',
  created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_valuation_source (source_movement_id),
  KEY idx_valuation_fifo (company_id, warehouse_id, item_id, status, layer_date, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存估值层';

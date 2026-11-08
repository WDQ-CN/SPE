-- E1-W3 续：批次与库位（docs/erp/31 §2、33 §2/§5、48）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- 库位是仓库主数据；批次由收货自动建档；库存余额按 物料+批次+库位 维度。

CREATE TABLE warehouse_location (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  warehouse_id BIGINT      NOT NULL COMMENT '仓库',
  code         VARCHAR(32) NOT NULL COMMENT '库位编码，仓库内唯一',
  name         VARCHAR(64) NOT NULL COMMENT '库位名称',
  zone         VARCHAR(32) NULL COMMENT '区域',
  aisle        VARCHAR(32) NULL COMMENT '巷道',
  rack         VARCHAR(32) NULL COMMENT '货架',
  level_no     VARCHAR(32) NULL COMMENT '层',
  bin_no       VARCHAR(32) NULL COMMENT '格',
  status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
               ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_location_code (warehouse_id, code),
  KEY idx_location_warehouse (warehouse_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '仓库库位';

CREATE TABLE inventory_lot (
  id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  lot_no          VARCHAR(64) NOT NULL COMMENT '批次号',
  item_id         BIGINT      NOT NULL COMMENT '物料',
  supplier_id     BIGINT      NULL COMMENT '来源供应商',
  source_type     VARCHAR(32) NULL COMMENT '来源单据类型',
  source_id       BIGINT      NULL COMMENT '来源单据 id',
  production_date DATE        NULL COMMENT '生产日期',
  expiry_date     DATE        NULL COMMENT '有效期至',
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/QUARANTINE/EXPIRED/CLOSED',
  version         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lot_item_no (item_id, lot_no),
  KEY idx_lot_item_status (item_id, status),
  KEY idx_lot_supplier (supplier_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存批次';

ALTER TABLE goods_receipt_line
  ADD COLUMN lot_no VARCHAR(64) NULL COMMENT '批次号（批次管理物料必填）',
  ADD COLUMN location_id BIGINT NOT NULL DEFAULT 0 COMMENT '库位；0=未分配';

ALTER TABLE purchase_return_line
  ADD COLUMN lot_no VARCHAR(64) NULL COMMENT '批次号（批次管理物料必填）',
  ADD COLUMN location_id BIGINT NOT NULL DEFAULT 0 COMMENT '库位；0=未分配';

ALTER TABLE inventory_movement
  ADD COLUMN lot_id BIGINT NOT NULL DEFAULT 0 COMMENT '批次；0=非批次',
  ADD COLUMN location_id BIGINT NOT NULL DEFAULT 0 COMMENT '库位；0=未分配',
  ADD KEY idx_movement_lot (company_id, item_id, lot_id);

ALTER TABLE inventory_stock
  ADD COLUMN lot_id BIGINT NOT NULL DEFAULT 0 COMMENT '批次；0=非批次',
  ADD COLUMN location_id BIGINT NOT NULL DEFAULT 0 COMMENT '库位；0=未分配';

ALTER TABLE inventory_stock
  DROP INDEX uk_stock_dim,
  ADD UNIQUE KEY uk_stock_dim
    (company_id, plant_id, warehouse_id, location_id, item_id, lot_id),
  ADD KEY idx_stock_lot (company_id, item_id, lot_id);

-- E2 W1：序列号、FEFO/效期查询（docs/erp/54 §3.1/§4.1/§4.2）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- 库存余额仍按 物料+批次+库位 聚合；序列号记录当前状态/位置，移动通过关联表追溯。

CREATE TABLE inventory_serial (
  id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  serial_no       VARCHAR(64) NOT NULL COMMENT '序列号，物料内唯一',
  item_id         BIGINT      NOT NULL COMMENT '物料',
  lot_id          BIGINT      NOT NULL DEFAULT 0 COMMENT '批次；0=非批次',
  supplier_id     BIGINT      NULL COMMENT '来源供应商',
  source_type     VARCHAR(32) NOT NULL COMMENT '来源单据类型',
  source_id       BIGINT      NOT NULL COMMENT '来源单据 id',
  source_line_id  BIGINT      NOT NULL COMMENT '来源单据行 id',
  warehouse_id    BIGINT      NOT NULL COMMENT '当前仓库',
  location_id     BIGINT      NOT NULL DEFAULT 0 COMMENT '当前库位；0=未分配',
  status          VARCHAR(16) NOT NULL DEFAULT 'IN_STOCK'
                  COMMENT 'IN_STOCK/ISSUED/SCRAPPED/RETURNED',
  version         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_serial_item_no (item_id, serial_no),
  KEY idx_serial_lot (lot_id),
  KEY idx_serial_warehouse_status (warehouse_id, location_id, status),
  KEY idx_serial_source (source_type, source_id, source_line_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存序列号';

CREATE TABLE inventory_movement_serial (
  movement_id BIGINT      NOT NULL COMMENT '库存移动',
  serial_id   BIGINT      NOT NULL COMMENT '序列号',
  direction   VARCHAR(4)  NOT NULL COMMENT 'IN/OUT',
  PRIMARY KEY (movement_id, serial_id),
  KEY idx_movement_serial_serial (serial_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存移动-序列号关联';

CREATE TABLE lot_expiry_alert (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  lot_id       BIGINT      NOT NULL COMMENT '批次',
  alert_date   DATE        NOT NULL COMMENT '扫描日期',
  days_before  INT         NOT NULL COMMENT '提前天数档位',
  sent_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lot_expiry_alert (lot_id, alert_date, days_before)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '批次效期预警去重';

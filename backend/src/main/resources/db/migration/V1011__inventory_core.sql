-- W3：库存台账与收货冲销（docs/erp/33、52）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- 收货过账生成 RECEIPT 移动；冲销生成 REVERSAL 移动并回退库存。
-- 移动不可删除/修改，来源唯一键保证重复事件不重复记账。

ALTER TABLE goods_receipt
  ADD COLUMN reversed_by BIGINT       NULL COMMENT '冲销人',
  ADD COLUMN reversed_at DATETIME(3)  NULL COMMENT '冲销时间',
  ADD COLUMN reverse_reason VARCHAR(200) NULL COMMENT '冲销原因';

CREATE TABLE inventory_movement (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  movement_no    VARCHAR(40)   NOT NULL COMMENT '库存移动号',
  company_id     BIGINT        NOT NULL COMMENT '公司',
  plant_id       BIGINT        NOT NULL COMMENT '工厂',
  warehouse_id   BIGINT        NOT NULL COMMENT '仓库',
  item_id        BIGINT        NOT NULL COMMENT '物料',
  movement_type  VARCHAR(16)   NOT NULL COMMENT 'RECEIPT/REVERSAL/ISSUE/ADJUST/RETURN',
  direction      VARCHAR(4)    NOT NULL COMMENT 'IN/OUT',
  qty            DECIMAL(19,6) NOT NULL COMMENT '移动数量（正数）',
  uom            VARCHAR(16)   NOT NULL COMMENT '单位',
  unit_cost      DECIMAL(19,4) NOT NULL COMMENT '单位成本',
  amount         DECIMAL(19,4) NOT NULL COMMENT '移动金额（正数）',
  currency       CHAR(3)       NOT NULL COMMENT '币种',
  source_type    VARCHAR(32)   NOT NULL COMMENT '来源单据类型',
  source_id      BIGINT        NOT NULL COMMENT '来源单据 id',
  source_line_id BIGINT        NOT NULL COMMENT '来源单据行 id',
  occurred_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间',
  created_by     BIGINT        NOT NULL COMMENT '操作人',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_movement_no (movement_no),
  UNIQUE KEY uk_movement_source (source_type, source_id, source_line_id, movement_type),
  KEY idx_movement_company_wh (company_id, warehouse_id, item_id, occurred_at),
  KEY idx_movement_source (source_type, source_id),
  CONSTRAINT ck_movement_qty CHECK (qty > 0),
  CONSTRAINT ck_movement_cost CHECK (unit_cost >= 0 AND amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存移动台账（不可变）';

CREATE TABLE inventory_stock (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id    BIGINT        NOT NULL COMMENT '公司',
  plant_id      BIGINT        NOT NULL COMMENT '工厂',
  warehouse_id  BIGINT        NOT NULL COMMENT '仓库',
  item_id       BIGINT        NOT NULL COMMENT '物料',
  qty_on_hand   DECIMAL(19,6) NOT NULL DEFAULT 0 COMMENT '现有量',
  total_value   DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '库存价值',
  avg_unit_cost DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '移动平均单价',
  version       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_dim (company_id, plant_id, warehouse_id, item_id),
  KEY idx_stock_warehouse (company_id, warehouse_id, item_id),
  CONSTRAINT ck_stock_qty CHECK (qty_on_hand >= 0),
  CONSTRAINT ck_stock_value CHECK (total_value >= 0 AND avg_unit_cost >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '库存余额（移动平均）';

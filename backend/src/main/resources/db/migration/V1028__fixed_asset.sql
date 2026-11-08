-- E5 固定资产：卡片、资本化、直线折旧、处置（docs/erp/38 §3）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

CREATE TABLE fixed_asset (
  id                     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  asset_no               VARCHAR(40)   NOT NULL COMMENT '资产号',
  company_id             BIGINT        NOT NULL COMMENT '公司',
  plant_id               BIGINT        NULL COMMENT '工厂',
  category               VARCHAR(50)   NOT NULL COMMENT '资产类别',
  name                   VARCHAR(200)  NOT NULL COMMENT '资产名称',
  source_type            VARCHAR(32)   NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/PURCHASE/PROJECT',
  source_id              BIGINT        NULL COMMENT '来源单据',
  cost                   DECIMAL(19,4) NOT NULL COMMENT '原值',
  salvage_value          DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '残值',
  useful_life_months     INT           NOT NULL COMMENT '使用寿命（月）',
  depreciation_method    VARCHAR(16)   NOT NULL DEFAULT 'STRAIGHT_LINE' COMMENT 'STRAIGHT_LINE',
  accumulated_depreciation DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '累计折旧',
  status                 VARCHAR(16)   NOT NULL DEFAULT 'PLANNED'
                         COMMENT 'PLANNED/ACTIVE/DISPOSED',
  last_depreciation_period VARCHAR(7)  NULL COMMENT '最后折旧期间 YYYY-MM',
  capitalized_at         DATETIME(3)   NULL COMMENT '资本化时间',
  disposed_at            DATETIME(3)   NULL COMMENT '处置时间',
  disposal_proceeds      DECIMAL(19,4) NULL COMMENT '处置收入',
  disposal_gain_loss     DECIMAL(19,4) NULL COMMENT '处置损益',
  version                INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by             BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at             DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fixed_asset_no (asset_no),
  KEY idx_fixed_asset_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '固定资产卡片';

CREATE TABLE asset_depreciation (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  asset_id           BIGINT        NOT NULL COMMENT '资产',
  period             VARCHAR(7)    NOT NULL COMMENT '期间 YYYY-MM',
  amount             DECIMAL(19,4) NOT NULL COMMENT '本期折旧',
  accumulated_after  DECIMAL(19,4) NOT NULL COMMENT '折旧后累计',
  posted_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '过账时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_depreciation (asset_id, period),
  KEY idx_asset_depreciation_period (period)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '资产折旧';

-- E1-W1a：组织与物料主数据（docs/erp/31、48、52）。
-- 只增不改：应用后本文件冻结（06 §1.3）。
-- 组织层级：company → plant → warehouse；交易表今后统一带 company_id。
-- 不使用外键：项目既有迁移约定用应用层校验 + 索引，避免删除/归档时的级联风险。

CREATE TABLE company (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code          VARCHAR(32)  NOT NULL COMMENT '公司编码，全局唯一',
  name          VARCHAR(128) NOT NULL COMMENT '公司名称',
  base_currency CHAR(3)      NOT NULL COMMENT '本位币 ISO 4217',
  tax_no        VARCHAR(64)  NULL COMMENT '税号（敏感字段，展示脱敏）',
  country       CHAR(2)      NOT NULL COMMENT '国家/地区 ISO 3166-1 alpha-2',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_company_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '公司/核算主体';

CREATE TABLE plant (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id BIGINT       NOT NULL COMMENT '所属公司',
  code       VARCHAR(32)  NOT NULL COMMENT '工厂编码，公司内唯一',
  name       VARCHAR(128) NOT NULL COMMENT '工厂名称',
  address    VARCHAR(255) NULL COMMENT '地址',
  status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version    INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
             ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_plant_company_code (company_id, code),
  KEY idx_plant_company (company_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '工厂';

CREATE TABLE warehouse (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  plant_id       BIGINT       NOT NULL COMMENT '所属工厂',
  code           VARCHAR(32)  NOT NULL COMMENT '仓库编码，工厂内唯一',
  name           VARCHAR(128) NOT NULL COMMENT '仓库名称',
  warehouse_type VARCHAR(16)  NOT NULL DEFAULT 'STANDARD'
                 COMMENT 'STANDARD/QUALITY/TRANSIT/CONSIGNMENT',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                 ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_warehouse_plant_code (plant_id, code),
  KEY idx_warehouse_plant (plant_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '仓库';

CREATE TABLE item (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code              VARCHAR(50)  NOT NULL COMMENT '物料编码，全局唯一',
  name              VARCHAR(200) NOT NULL COMMENT '物料名称',
  spec              VARCHAR(255) NULL COMMENT '规格/图号',
  item_type         VARCHAR(16)  NOT NULL DEFAULT 'RAW'
                    COMMENT 'RAW/WIP/FINISHED/SERVICE/EXPENSE',
  base_uom          VARCHAR(16)  NOT NULL COMMENT '基本计量单位',
  lot_controlled    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否批次管理',
  serial_controlled TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否序列号管理',
  valuation_method  VARCHAR(16)  NOT NULL DEFAULT 'AVCO'
                    COMMENT 'FIFO/AVCO/STANDARD',
  status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  version           INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_item_code (code),
  KEY idx_item_status_type (status, item_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '物料/服务主数据';

CREATE TABLE item_uom_conversion (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  item_id     BIGINT        NOT NULL COMMENT '物料',
  from_uom    VARCHAR(16)   NOT NULL COMMENT '源计量单位',
  to_uom      VARCHAR(16)   NOT NULL COMMENT '目标计量单位',
  numerator   DECIMAL(19,6) NOT NULL COMMENT '换算分子',
  denominator DECIMAL(19,6) NOT NULL COMMENT '换算分母',
  version     INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_item_uom (item_id, from_uom, to_uom),
  KEY idx_item_uom_item (item_id),
  CONSTRAINT ck_item_uom_numerator CHECK (numerator > 0),
  CONSTRAINT ck_item_uom_denominator CHECK (denominator > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '物料计量单位换算';

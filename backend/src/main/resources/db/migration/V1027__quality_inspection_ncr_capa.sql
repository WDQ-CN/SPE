-- E5 质量：检验计划/检验单/结果、NCR、CAPA、供应商证书（docs/erp/37 §5）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

CREATE TABLE inspection_plan (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  plan_no       VARCHAR(40)  NOT NULL COMMENT '计划号',
  company_id    BIGINT       NOT NULL COMMENT '公司',
  item_id       BIGINT       NOT NULL COMMENT '物料',
  supplier_id   BIGINT       NULL COMMENT '供应商（来料检验）',
  inspection_type VARCHAR(16) NOT NULL DEFAULT 'IQC' COMMENT 'IQC/IPQC/FQC',
  version       VARCHAR(16)  NOT NULL DEFAULT 'V1' COMMENT '版本',
  status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/RETIRED',
  sampling_plan VARCHAR(64)  NULL COMMENT '抽样方案',
  aql           DECIMAL(9,6) NULL COMMENT 'AQL',
  created_by    BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  activated_at  DATETIME(3)  NULL COMMENT '生效时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inspection_plan_no (plan_no),
  KEY idx_inspection_plan_item (item_id, supplier_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '检验计划';

CREATE TABLE inspection_plan_characteristic (
  id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  plan_id   BIGINT       NOT NULL COMMENT '计划',
  line_no   INT          NOT NULL COMMENT '行号',
  name      VARCHAR(100) NOT NULL COMMENT '特性',
  spec      VARCHAR(255) NULL COMMENT '规格/标准',
  critical  TINYINT      NOT NULL DEFAULT 0 COMMENT '关键特性',
  method    VARCHAR(64)  NULL COMMENT '检验方法',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inspection_characteristic (plan_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '检验特性';

CREATE TABLE inspection_order (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_no       VARCHAR(40)   NOT NULL COMMENT '检验单号',
  company_id     BIGINT        NOT NULL COMMENT '公司',
  plan_id        BIGINT        NOT NULL COMMENT '检验计划',
  source_type    VARCHAR(32)   NOT NULL DEFAULT 'MANUAL' COMMENT 'GOODS_RECEIPT/WORK_ORDER/MANUAL',
  source_id      BIGINT        NULL COMMENT '来源单据',
  source_line_id BIGINT        NULL COMMENT '来源行',
  item_id        BIGINT        NOT NULL COMMENT '物料',
  supplier_id    BIGINT        NULL COMMENT '供应商',
  lot_no         VARCHAR(64)   NULL COMMENT '批次',
  qty            DECIMAL(19,6) NOT NULL COMMENT '检验数量',
  status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                 COMMENT 'PENDING/IN_PROGRESS/PASSED/FAILED/DEVIATED',
  created_by     BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  completed_at   DATETIME(3)   NULL COMMENT '完成时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inspection_order_no (order_no),
  KEY idx_inspection_order_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET=utf8mb4 COMMENT = '检验单';

CREATE TABLE inspection_result (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  inspection_id     BIGINT       NOT NULL COMMENT '检验单',
  line_no           INT          NOT NULL COMMENT '行号',
  characteristic_id BIGINT       NULL COMMENT '检验特性',
  name              VARCHAR(100) NOT NULL COMMENT '特性名',
  measured_value    VARCHAR(100) NULL COMMENT '实测值',
  result            VARCHAR(16)  NOT NULL COMMENT 'PASS/FAIL',
  remark            VARCHAR(255) NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inspection_result (inspection_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '检验结果';

CREATE TABLE ncr (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  ncr_no          VARCHAR(40)   NOT NULL COMMENT 'NCR 号',
  company_id      BIGINT        NOT NULL COMMENT '公司',
  source_type     VARCHAR(32)   NOT NULL DEFAULT 'INSPECTION' COMMENT 'INSPECTION/MANUAL',
  source_id       BIGINT        NULL COMMENT '来源单据',
  inspection_id   BIGINT        NULL COMMENT '检验单',
  item_id         BIGINT        NOT NULL COMMENT '物料',
  supplier_id     BIGINT        NULL COMMENT '供应商',
  lot_no          VARCHAR(64)   NULL COMMENT '批次',
  qty             DECIMAL(19,6) NOT NULL COMMENT '不合格数量',
  defect          VARCHAR(255)  NOT NULL COMMENT '缺陷描述',
  status          VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/DISPOSITIONED/CLOSED',
  disposition     VARCHAR(24)   NULL COMMENT 'REWORK/SCRAP/RETURN/DEVIATE/USE_AS_IS',
  reason          VARCHAR(255)  NULL COMMENT '处置原因',
  created_by      BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  dispositioned_at DATETIME(3)  NULL COMMENT '处置时间',
  closed_at       DATETIME(3)   NULL COMMENT '关闭时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ncr_no (ncr_no),
  KEY idx_ncr_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '不合格品报告';

CREATE TABLE capa (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  capa_no         VARCHAR(40)  NOT NULL COMMENT 'CAPA 号',
  company_id      BIGINT       NOT NULL COMMENT '公司',
  ncr_id          BIGINT       NULL COMMENT '来源 NCR',
  supplier_id     BIGINT       NULL COMMENT '供应商',
  root_cause      VARCHAR(500) NULL COMMENT '根因',
  action_plan     VARCHAR(1000) NOT NULL COMMENT '措施计划',
  status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/IN_PROGRESS/VERIFIED/CLOSED',
  created_by      BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  verified_by     BIGINT       NULL COMMENT '验证人',
  verified_at     DATETIME(3)  NULL COMMENT '验证时间',
  closed_at       DATETIME(3)  NULL COMMENT '关闭时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_capa_no (capa_no),
  KEY idx_capa_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '纠正预防措施';

CREATE TABLE supplier_certificate (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_id  BIGINT       NOT NULL COMMENT '公司',
  supplier_id BIGINT       NOT NULL COMMENT '供应商',
  item_id     BIGINT       NULL COMMENT '物料（可空）',
  cert_type   VARCHAR(32)  NOT NULL COMMENT 'ISO9001/CoA/CoC/OTHER',
  cert_no     VARCHAR(64)  NOT NULL COMMENT '证书编号',
  valid_from  DATE         NOT NULL COMMENT '生效日',
  valid_to    DATE         NOT NULL COMMENT '失效日',
  status      VARCHAR(16)  NOT NULL DEFAULT 'VALID' COMMENT 'VALID/REVOKED',
  created_by  BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_cert_no (company_id, supplier_id, cert_no),
  KEY idx_supplier_cert_valid (company_id, valid_to, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商证书';

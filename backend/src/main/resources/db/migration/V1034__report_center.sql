-- E6 报表中心：定义目录与运行快照（docs/erp/39 §1）。
-- 只增不改：应用后本文件冻结（06 §1.3）。查询为代码内固定 SQL，不接受用户 SQL。

CREATE TABLE report_definition (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code        VARCHAR(64)  NOT NULL COMMENT '报表编码',
  name        VARCHAR(200) NOT NULL COMMENT '报表名称',
  query_key   VARCHAR(64)  NOT NULL COMMENT '固定查询键',
  description VARCHAR(500) NULL COMMENT '说明',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_report_definition_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '报表定义';

CREATE TABLE report_run (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  definition_id BIGINT       NOT NULL COMMENT '报表定义',
  company_id    BIGINT       NOT NULL COMMENT '公司',
  as_of         DATE         NULL COMMENT '截止日期',
  status        VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED',
  row_count     INT          NOT NULL DEFAULT 0 COMMENT '行数',
  result_json   LONGTEXT     NULL COMMENT '结果 JSON',
  error_message VARCHAR(500) NULL COMMENT '失败原因',
  duration_ms   BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时毫秒',
  requested_by  BIGINT       NOT NULL DEFAULT 0 COMMENT '请求人',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_report_run_definition (definition_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '报表运行';

INSERT INTO report_definition (code, name, query_key, description, status) VALUES
  ('INVENTORY_STOCK', '库存余额', 'INVENTORY_STOCK', '按仓库/物料汇总在库数量与金额', 'ACTIVE'),
  ('AR_AGING', '应收账龄', 'AR_AGING', '销售发票未收金额按到期日分桶', 'ACTIVE'),
  ('AP_OUTSTANDING', '应付未付', 'AP_OUTSTANDING', '供应商发票未付金额与到期日', 'ACTIVE'),
  ('PROJECT_COST', '项目成本', 'PROJECT_COST', '项目累计成本与预算', 'ACTIVE'),
  ('WORK_ORDER_STATUS', '工单状态', 'WORK_ORDER_STATUS', '按状态汇总工单数量与材料成本', 'ACTIVE'),
  ('AUDIT_SUMMARY', '审计动作汇总', 'AUDIT_SUMMARY', '按动作统计审计条数', 'ACTIVE');

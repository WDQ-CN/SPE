-- E5 项目：项目/WBS/预算/工时/项目成本（docs/erp/38 §2）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

CREATE TABLE project (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  project_no         VARCHAR(40)   NOT NULL COMMENT '项目号',
  company_id         BIGINT        NOT NULL COMMENT '公司',
  customer_id        BIGINT        NULL COMMENT '客户（可空）',
  name               VARCHAR(200)  NOT NULL COMMENT '项目名称',
  currency           CHAR(3)       NOT NULL COMMENT '币种',
  budget_amount      DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '批准预算',
  default_labor_rate DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '默认人工费率/小时',
  status             VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'
                     COMMENT 'DRAFT/APPROVED/ACTIVE/CLOSED',
  start_date         DATE          NULL COMMENT '开始日期',
  end_date           DATE          NULL COMMENT '结束日期',
  manager_id         BIGINT        NOT NULL DEFAULT 0 COMMENT '项目经理',
  created_by         BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  approved_by        BIGINT        NULL COMMENT '审批人',
  approved_at        DATETIME(3)   NULL COMMENT '审批时间',
  closed_at          DATETIME(3)   NULL COMMENT '关闭时间',
  version            INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_no (project_no),
  KEY idx_project_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '项目';

CREATE TABLE project_wbs (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  project_id  BIGINT        NOT NULL COMMENT '项目',
  parent_id   BIGINT        NULL COMMENT '父 WBS',
  code        VARCHAR(32)   NOT NULL COMMENT 'WBS 编码',
  name        VARCHAR(200)  NOT NULL COMMENT 'WBS 名称',
  budget      DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT 'WBS 预算',
  status      VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_wbs_code (project_id, code),
  KEY idx_project_wbs (project_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '项目 WBS';

CREATE TABLE project_budget (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  project_id      BIGINT        NOT NULL COMMENT '项目',
  version_no      VARCHAR(16)   NOT NULL COMMENT '预算版本',
  labor_amount    DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '人工预算',
  purchase_amount DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '采购预算',
  expense_amount  DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '费用预算',
  other_amount    DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '其他预算',
  total_amount    DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '合计',
  status          VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/APPROVED',
  created_by      BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  approved_by     BIGINT        NULL COMMENT '审批人',
  approved_at     DATETIME(3)   NULL COMMENT '审批时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_budget_version (project_id, version_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '项目预算';

CREATE TABLE project_timesheet (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  timesheet_no  VARCHAR(40)   NOT NULL COMMENT '工时单号',
  company_id    BIGINT        NOT NULL COMMENT '公司',
  user_id       BIGINT        NOT NULL COMMENT '填报人',
  status        VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'
                COMMENT 'DRAFT/SUBMITTED/APPROVED/POSTED',
  total_hours   DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '总工时',
  total_amount  DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '总人工成本',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  submitted_at  DATETIME(3)   NULL COMMENT '提交时间',
  approved_by   BIGINT        NULL COMMENT '审批人',
  approved_at   DATETIME(3)   NULL COMMENT '审批时间',
  version       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_timesheet_no (timesheet_no),
  KEY idx_timesheet_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '项目工时单';

CREATE TABLE project_timesheet_line (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  timesheet_id BIGINT        NOT NULL COMMENT '工时单',
  line_no      INT           NOT NULL COMMENT '行号',
  project_id   BIGINT        NOT NULL COMMENT '项目',
  wbs_id       BIGINT        NULL COMMENT 'WBS',
  work_date    DATE          NOT NULL COMMENT '工作日期',
  hours        DECIMAL(9,2)  NOT NULL COMMENT '工时',
  rate         DECIMAL(19,4) NOT NULL COMMENT '人工费率',
  amount       DECIMAL(19,4) NOT NULL COMMENT '人工成本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_timesheet_line (timesheet_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '项目工时行';

CREATE TABLE project_cost (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  project_id  BIGINT        NOT NULL COMMENT '项目',
  source_type VARCHAR(32)   NOT NULL COMMENT 'TIMESHEET/PURCHASE/MANUAL',
  source_id   BIGINT        NOT NULL COMMENT '来源单据',
  source_line_id BIGINT     NULL COMMENT '来源行',
  amount      DECIMAL(19,4) NOT NULL COMMENT '成本金额',
  currency    CHAR(3)       NOT NULL COMMENT '币种',
  posted_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '过账时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_cost_source (source_type, source_id, source_line_id),
  KEY idx_project_cost (project_id, posted_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '项目成本';

-- E6 通用工作流：定义/节点/实例/任务（docs/erp/39 §2）。
-- 只增不改：应用后本文件冻结（06 §1.3）。E6 先做串行角色审批，会签/委托/SLA 后续。

CREATE TABLE workflow_definition (
  id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  code           VARCHAR(64)   NOT NULL COMMENT '定义编码',
  name           VARCHAR(200)  NOT NULL COMMENT '名称',
  doc_type       VARCHAR(64)   NOT NULL COMMENT '适用单据类型',
  version_no     VARCHAR(16)   NOT NULL COMMENT '版本',
  condition_min_amount DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '金额下限',
  condition_max_amount DECIMAL(19,4) NULL COMMENT '金额上限',
  status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/INACTIVE',
  created_by     BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  activated_at   DATETIME(3)   NULL COMMENT '生效时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_workflow_code_version (code, version_no),
  KEY idx_workflow_doc (doc_type, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '工作流定义';

CREATE TABLE workflow_node (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  definition_id BIGINT       NOT NULL COMMENT '定义',
  seq_no        INT          NOT NULL COMMENT '顺序',
  name          VARCHAR(100) NOT NULL COMMENT '节点名称',
  approver_role VARCHAR(32)  NOT NULL COMMENT '审批角色',
  PRIMARY KEY (id),
  UNIQUE KEY uk_workflow_node (definition_id, seq_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '工作流节点';

CREATE TABLE workflow_instance (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  instance_no   VARCHAR(40)   NOT NULL COMMENT '实例号',
  definition_id BIGINT        NOT NULL COMMENT '定义',
  doc_type      VARCHAR(64)   NOT NULL COMMENT '单据类型',
  doc_id        BIGINT        NOT NULL COMMENT '单据 ID',
  company_id    BIGINT        NOT NULL COMMENT '公司',
  amount        DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '金额（条件用）',
  status        VARCHAR(16)   NOT NULL DEFAULT 'RUNNING'
                COMMENT 'RUNNING/APPROVED/REJECTED/CANCELED',
  current_seq   INT           NOT NULL DEFAULT 1 COMMENT '当前节点顺序',
  started_by    BIGINT        NOT NULL DEFAULT 0 COMMENT '发起人',
  started_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发起时间',
  completed_at  DATETIME(3)   NULL COMMENT '完成时间',
  version       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_workflow_instance_no (instance_no),
  KEY idx_workflow_instance_doc (doc_type, doc_id),
  KEY idx_workflow_instance_status (company_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '工作流实例';

CREATE TABLE workflow_task (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  instance_id   BIGINT       NOT NULL COMMENT '实例',
  seq_no        INT          NOT NULL COMMENT '节点顺序',
  approver_role VARCHAR(32)  NOT NULL COMMENT '审批角色',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                COMMENT 'PENDING/APPROVED/REJECTED/CANCELED',
  assigned_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '分派时间',
  acted_by      BIGINT       NULL COMMENT '处理人',
  acted_at      DATETIME(3)  NULL COMMENT '处理时间',
  opinion       VARCHAR(500) NULL COMMENT '意见',
  PRIMARY KEY (id),
  UNIQUE KEY uk_workflow_instance_seq (instance_id, seq_no),
  KEY idx_workflow_task_status (status, approver_role)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '工作流任务';

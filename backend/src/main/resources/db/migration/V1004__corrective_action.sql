-- 整改闭环（29 §3.1）：预警结构化字段 + 整改任务表。
-- D4：TOTAL_LOW 目标=总分阈值；KEY_DIMENSION_LOW=关键维度阈值；TREND_DECLINE=本期基线。
-- D5：下期由 CorrectiveActionMapper 按“供应商 + 周期严格晚于基线 + EFFECTIVE”取最早一条。
-- D7：预警被重算 supersede 时，未完成整改由 AlertListener 批量取消。
-- D8：旧预警的 baseline/target 为空，只允许原“标记已处理”，不提供创建整改。
-- 只增不改：应用后本文件冻结（04-075/077 的教训）。

ALTER TABLE alert
  ADD COLUMN dimension_id  BIGINT       NULL COMMENT '关键维度预警的维度 id；总分/趋势为空',
  ADD COLUMN baseline_score DECIMAL(6,2) NULL COMMENT '触发预警时的分数（总分或维度分）',
  ADD COLUMN target_score  DECIMAL(6,2) NULL COMMENT '整改目标分（阈值或基线）';

CREATE TABLE corrective_action (
  id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  alert_id             BIGINT       NOT NULL COMMENT '来源预警（一条预警最多一条整改）',
  task_supplier_id     BIGINT       NOT NULL COMMENT '基线评估单',
  supplier_id          BIGINT       NOT NULL COMMENT '供应商',
  dimension_id         BIGINT       NULL COMMENT '关键维度整改；总分/趋势为空',
  baseline_year        INT          NOT NULL COMMENT '基线周期-年',
  baseline_quarter     TINYINT      NOT NULL COMMENT '基线周期-季度 1~4',
  baseline_score       DECIMAL(6,2) NOT NULL COMMENT '基线分',
  target_score         DECIMAL(6,2) NOT NULL COMMENT '整改目标分',
  owner_id             BIGINT       NOT NULL COMMENT '整改负责人',
  due_date             DATE         NOT NULL COMMENT '整改截止日期',
  measure              VARCHAR(1000) NOT NULL COMMENT '整改措施',
  expected_result      VARCHAR(500) NULL COMMENT '预期结果',
  status               VARCHAR(24)  NOT NULL DEFAULT 'OPEN'
                       COMMENT 'OPEN/IN_PROGRESS/WAITING_VERIFICATION/VERIFIED/REOPENED/CANCELED',
  submit_comment       VARCHAR(500) NULL COMMENT '负责人提交说明',
  submitted_at         DATETIME(3)  NULL COMMENT '提交时间',
  verify_year          INT          NULL COMMENT '下期验证周期-年',
  verify_quarter       TINYINT      NULL COMMENT '下期验证周期-季度 1~4',
  verify_task_supplier_id BIGINT    NULL COMMENT '下期评估单（报告链接）',
  verify_score         DECIMAL(6,2) NULL COMMENT '下期实际分',
  verify_outcome       VARCHAR(16)  NULL COMMENT 'IMPROVED/NOT_IMPROVED/PENDING',
  verified_by          BIGINT       NULL COMMENT '验证人',
  verify_comment       VARCHAR(500) NULL COMMENT '验证意见',
  verified_at          DATETIME(3)  NULL COMMENT '验证时间',
  canceled_reason      VARCHAR(500) NULL COMMENT '取消原因',
  created_by           BIGINT       NOT NULL COMMENT '创建人',
  version              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_action_alert (alert_id),
  KEY idx_action_owner_status_due (owner_id, status, due_date),
  KEY idx_action_supplier_period (supplier_id, baseline_year, baseline_quarter),
  KEY idx_action_status_created (status, created_at, id),
  CONSTRAINT ck_action_quarter CHECK (baseline_quarter BETWEEN 1 AND 4),
  CONSTRAINT ck_action_verify_quarter CHECK
    (verify_quarter IS NULL OR verify_quarter BETWEEN 1 AND 4)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '整改任务（CapA）';

-- M5 W1（22 §2 D1）：预警记录。规则存 sys_setting.alert.rules（01 SM3），不建 alert_rule 表。
-- 只增不改：应用后本文件冻结（04-075/077 的教训）。
CREATE TABLE alert (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_supplier_id BIGINT       NOT NULL COMMENT '评估单（定稿/解锁的锚点）',
  supplier_id      BIGINT       NOT NULL COMMENT '供应商（跨期查询）',
  year             INT          NOT NULL COMMENT '周期-年',
  quarter          TINYINT      NOT NULL COMMENT '周期-季度 1~4',
  type             VARCHAR(32)  NOT NULL COMMENT 'TOTAL_LOW/KEY_DIMENSION_LOW/TREND_DECLINE',
  title            VARCHAR(100) NOT NULL COMMENT '标题（列表展示）',
  content          VARCHAR(500) NOT NULL COMMENT '内容（供应商/周期/分值/维度）',
  status           VARCHAR(20)  NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD/HANDLED/SUPERSEDED',
  handled_by       BIGINT       NULL COMMENT '处理人 id（展示时 join sys_user）',
  handled_comment  VARCHAR(500) NULL COMMENT '处理备注（可选）',
  handled_at       DATETIME(3)  NULL COMMENT '处理时间',
  superseded_by    BIGINT       NULL COMMENT '被哪条新预警取代；重算未命中则为空',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_alert_status_created (status, created_at),
  KEY idx_alert_supplier_period_type (supplier_id, year, quarter, type),
  CONSTRAINT ck_alert_quarter CHECK (quarter >= 1 AND quarter <= 4)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '预警记录（M5）';

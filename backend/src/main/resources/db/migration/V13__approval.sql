-- V13：审批与定级（evaluation 上下文的两张新表 + task_supplier 的三处变更）。
-- 依据 15 §E2/E3/E11/E16/E17、02 §3.3（列清单）、02 §3（状态机）、05 §8.2（键与索引）。
-- 脚本合入后不可修改（06 §1.3）。

-- 三件事：
--   1. approval_record                  审批记录（只增不改：一轮一节点一条终局记录）
--   2. task_supplier_dimension_result   维度结果快照（定稿时整批写、之后只读）
--   3. task_supplier 的三处变更：加 `round` 列；补待办查询的索引；更正 current_node 的列注释

-- 刻意的设计（逐条依据见 15 §E2/E3/E11）：
--   1. 两张新表都**不设 `version`**：它们是只增不改的记录/快照型表，不存在「并发改同一行」的语义
--      （对比 V11 的五表：那些要并发迁移状态，故带 version）。
--   2. `approval_record` 的唯一键含 `round`：这是「同一节点不能被审两次」的**数据库兜底**，
--      代价是驳回重审必须递增 round —— 那条不变量由 T5 的守卫 UPDATE 保证（15 §E5）。
--   3. 快照表冗余 `dimension_name` / `weight`：模板版本发布即冻结（P2 的版本化保证），
--      冗余值不会漂移，换来报告页「免实时联表」（05 §8.2）。
--   4. `ADD COLUMN round` 显式写 `ALGORITHM = INSTANT`：真实库**首次**加这一列
--      （15 §4.1 V2 只在一次性探针库验过），INSTANT 避免重建表与长时间锁表。
--   5. `current_node` 的列注释在 V11 里写的是「驳回重置为 1」——**那是错的**（P4 规划时裁定为
--      清零 0：驳回后状态是「评分中」、没有待审节点）。V11 已合入不可改（06 §1.3），故在此
--      用 MODIFY 更正注释，让列注释与 02 §3 / 15 §E5 一致。
--   ⚠️ 三条 ALTER **必须分开写**：`ALGORITHM = INSTANT` 只适用于 ADD COLUMN，
--      加索引与 MODIFY 不适用（混在一条里会因算法不匹配而失败）。

CREATE TABLE approval_record (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_supplier_id BIGINT       NOT NULL COMMENT '评估单（task_supplier.id，不建外键：06 §4）',
  round            INT          NOT NULL DEFAULT 1 COMMENT '轮次：驳回重提后同一 seq 会再次产生记录（15 §E11）',
  seq              INT          NOT NULL COMMENT '节点序号，与 approval_node.seq 同口径',
  approver_id      BIGINT       NOT NULL COMMENT '审批人（具体用户，不限角色：01 §3）',
  action           VARCHAR(20)  NOT NULL COMMENT '动作：APPROVE / REJECT',
  comment          VARCHAR(500) NULL COMMENT '审批意见；驳回必填由服务层判（01 FR-P1），不靠列约束',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_approval_record (task_supplier_id, round, seq),
  KEY idx_approval_record_tl (task_supplier_id, round, seq, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '审批记录（只增不改，P4）';

CREATE TABLE task_supplier_dimension_result (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_supplier_id BIGINT       NOT NULL COMMENT '评估单（task_supplier.id）',
  dimension_id     BIGINT       NOT NULL COMMENT '维度（模板版本内的 dimension.id）',
  dimension_name   VARCHAR(50)  NOT NULL COMMENT '维度名（冗余：报告免联表，05 §8.2；模板版本冻结故不漂移）',
  weight           DECIMAL(5,2) NOT NULL COMMENT '维度权重（冗余：定稿当时的快照口径）',
  score            DECIMAL(6,2) NULL COMMENT '维度分；不完整时为空（与 ScoreCalculator 的 null 口径一致）',
  grade            VARCHAR(2)   NULL COMMENT '维度等级 A/B/C/D',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ts_dimension (task_supplier_id, dimension_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '维度结果快照（定稿时写，P4）';

-- 1/3：加轮次列（INSTANT，不重建表）
ALTER TABLE task_supplier
  ADD COLUMN round INT NOT NULL DEFAULT 1 COMMENT '审批轮次：驳回重提 +1（15 §E11）',
  ALGORITHM = INSTANT;

-- 2/3：待办查询（「当前节点是我」）在 task_supplier 侧的驱动条件。
-- 沙盒 EXPLAIN 曾显示该侧走 type=ALL + hash join（15 §4.1 V6），故此索引是那次验证当场抓出的缺口。
ALTER TABLE task_supplier
  ADD KEY idx_ts_status_node (status, current_node);

-- 3/3：更正 V11 里那句错误的列注释（V11 已合入不可改）
ALTER TABLE task_supplier
  MODIFY COLUMN current_node INT NOT NULL DEFAULT 0
    COMMENT '当前审批节点序号；提交时置 1（T2），驳回清零为 0（02 §3、15 §E5）';

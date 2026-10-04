-- V11：评估流程五表（01 J1~J4、E1~E6；02 §3.1；05 §8.2；13 §E1/E2/E10/E13）。脚本合入后不可修改（06 §1.3）。
--
-- 五表构成「任务 → 评估单 → 评分单 → 评分记录」以及「任务上的审批链定义」：
--   eval_task ─< task_supplier ─< sheet ─< score
--   eval_task ─< approval_node
-- 列清单的逐列依据是 02 §3.1（2026-09-27 入口 pre-flight 已与 06 §2.1 的状态常量、13 §E9 的列名、
-- 13 §E2 的批次号长度对齐）；唯一键与索引锚定 05 §8.2，与建表同脚本建齐（06 §1.3 禁后补索引脚本）。
--
-- 四处刻意的设计：
--   1. 五表都不建外键：06 §4 的既定取向（引用完整性由服务层守卫，避免级联删除与锁范围扩大）。
--   2. 两个唯一键承担并发兜底：uk_task_supplier(task_id, supplier_id)（发布幂等；13 §E10 说明并发下
--      没有别的兜底，服务层必须把重复键翻译成 3xxx）；uk_sheet(task_supplier_id, dimension_id)
--      （「一个评估单每个维度只有一张评分单」，2026-09-27 拍板由数据库兜底，见 02 §3.1 拍板②）。
--      注意 01 J4（同一供应商同一周期只允许一个非取消任务）**无法**用唯一键表达：它是条件唯一，
--      而 MySQL 没有部分索引（13 §E10）——只能由服务层在发布事务内做当前读校验。
--   3. score 是「一行一指标」的 upsert 目标（uk_score；01 §6.7 的重复导入覆盖）：**不设 version**，
--      同一格并发写为后写覆盖；状态守卫落在 sheet.status 上（13 §E7/E8）。
--   4. total_score / total_grade 可空：P4 定稿时才写（02 §3.1 拍板①），使「没算过」与「0 分」不混淆。
--
-- 状态常量以 06 §2.1 为权威；本阶段只走得到其中一部分，其余按 07 §4「枚举预留、入口后置」先留着：
--   eval_task.status     DRAFT / IN_PROGRESS / COMPLETED / CANCELED（CANCELED 仅预留，J6 后置）
--   task_supplier.status SCORING / PENDING_APPROVAL / APPROVING / EFFECTIVE（驳回回 SCORING）
--   sheet.status         UNASSIGNED / SCORING / SUBMITTED
--   score.source         MANUAL / IMPORT / API

CREATE TABLE eval_task (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name                VARCHAR(100) NOT NULL COMMENT '任务名称（01 J1）',
  year                INT          NOT NULL COMMENT '评估周期-年，与 quarter 一起构成周期（05 §8.1）',
  quarter             TINYINT      NOT NULL COMMENT '评估周期-季度 1~4（05 §8.1）',
  template_version_id BIGINT       NOT NULL COMMENT '绑定的已发布模板版本（01 J1；快照由 TemplateSnapshotFacade 提供，13 §E4）',
  deadline            DATE         NOT NULL COMMENT '评估截止日期（01 J1）',
  status              VARCHAR(20)  NOT NULL COMMENT '状态：DRAFT/IN_PROGRESS/COMPLETED/CANCELED（CANCELED 仅预留，07 §59）',
  version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本：发布与草稿编辑都要守卫（06 §2.4）',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_eval_task_version (template_version_id),
  KEY idx_eval_task_status (status, deadline),
  CONSTRAINT ck_eval_task_quarter CHECK (quarter >= 1 AND quarter <= 4)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '评估任务';

CREATE TABLE task_supplier (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_id      BIGINT       NOT NULL COMMENT '所属评估任务',
  supplier_id  BIGINT       NOT NULL COMMENT '供应商（引用 supplier.id 而非编码：编码可改，01 S2）',
  status       VARCHAR(20)  NOT NULL COMMENT '状态：SCORING/PENDING_APPROVAL/APPROVING/EFFECTIVE（驳回回 SCORING，02 §3）',
  current_node INT          NOT NULL DEFAULT 0 COMMENT '当前审批节点序号；驳回重置为 1（02 §3、05 §5.5 T2）',
  total_score  DECIMAL(6,2) NULL COMMENT '总分：P4 定稿时才写，故可空（02 §3.1 拍板①）',
  total_grade  VARCHAR(2)   NULL COMMENT '总等级 A/B/C/D：与总分同时写，故可空（01 §6.3）',
  version      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本：状态迁移一律带前置状态条件（06 §2.4）',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_supplier (task_id, supplier_id),
  KEY idx_task_supplier_supplier (supplier_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '评估单（一个任务里的一个供应商）';

CREATE TABLE approval_node (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_id     BIGINT      NOT NULL COMMENT '所属评估任务（审批链挂在任务上，02 §3）',
  approver_id BIGINT      NOT NULL COMMENT '审批人（sys_user.id；发布时校验在职，01 §7）',
  seq         INT         NOT NULL COMMENT '链内序号，从 1 起（串行审批的先后顺序，01 J1）',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_approval_node (task_id, seq),
  KEY idx_approval_node_approver (approver_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '审批链定义（挂在任务上，P3 只建与读）';

CREATE TABLE sheet (
  id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_supplier_id BIGINT      NOT NULL COMMENT '所属评估单',
  dimension_id     BIGINT      NOT NULL COMMENT '维度（引用发布版本的 dimension.id）',
  assignee_id      BIGINT      NULL COMMENT '指派的评估人；待指派（UNASSIGNED）时为空（01 J2）',
  status           VARCHAR(20) NOT NULL COMMENT '状态：UNASSIGNED/SCORING/SUBMITTED（改派仅限前两者，02 §3）',
  version          INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本：提交靠前置状态条件守卫（06 §2.4、13 §E9）',
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sheet (task_supplier_id, dimension_id),
  KEY idx_sheet_assignee_status (assignee_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '评分单（一个评估单的一个维度）';

CREATE TABLE score (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  sheet_id     BIGINT        NOT NULL COMMENT '所属评分单',
  indicator_id BIGINT        NOT NULL COMMENT '指标（引用发布版本的 indicator.id）',
  raw_value    DECIMAL(12,4) NULL COMMENT '原始录入值：定量 0~100、定性 1~5；精度与 indicator.target_value 一致（01 §6.6、13 §E2）',
  score_value  DECIMAL(6,2)  NULL COMMENT '换算后的百分制分：写入时就算，前端不得传分（01 E2、13 §E8）',
  source       VARCHAR(10)   NOT NULL COMMENT '来源：MANUAL/IMPORT/API，记最后一次写入渠道（01 §6.7）',
  import_batch VARCHAR(32)   NULL COMMENT 'Excel 批次号：仅本次写入来自 IMPORT 时写，其它来源置 NULL（01 §6.7、13 §E2）',
  created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_score (sheet_id, indicator_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '评分记录（一行一指标，三源 upsert 的目标；无 version，见脚本头第 3 条）';

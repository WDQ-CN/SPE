-- V12：站内通知（notify 上下文的第一张表；01 SM6 最小版、02 §3.2、05 §8.2、13 §E1/E16）。脚本合入后不可修改（06 §1.3）。
--
-- 范围：P3 只做 `TaskPublishedEvent` 一条链路产生的通知（发布任务 → 给相关人各写一行），
-- 对外只有「列表 / 未读角标 / 全部已读」三个动作（13 §E16）。通知中心页、按类型筛选等完整版属 MVP+（07 §34）。
--
-- 三处刻意的设计（逐列依据是 02 §3.2，2026-09-27 入口 pre-flight 起草、2026-10-01 随 W1.2 落定）：
--   1. `type` 用 VARCHAR(30) 存枚举常量而不是 TINYINT：与 05 §8.1「状态/类型/来源用 VARCHAR 存枚举常量」
--      的既定口径一致；P3 只会写入 `TASK_PUBLISHED`，取值**只增不改**（06 §2.8 的同族纪律）。
--   2. 只记 `is_read`，**不设 `read_at`**：最小版只需要未读角标与「全部已读」，记时间没有消费方
--      ——留一个没人读的列就是 CLAUDE 第 1 条点名的「声明了但没人消费」。
--   3. **无 `updated_at`**：通知创建后不可修改（没有编辑入口），`is_read` 的写入时间由 `audit_log` 承担。
--
-- 键：`idx_notification_receiver_read (receiver_id, is_read)` = 05 §8.2 的 `idx(notification.receiver_id, is_read)`，
-- 未读角标（`where receiver_id=? and is_read=0`）与列表（`where receiver_id=?`）共用同一个索引前缀。
-- 列表按 `id DESC` 排（自增主键即时间序），因此不另建 `created_at` 排序索引。

CREATE TABLE notification (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  receiver_id BIGINT       NOT NULL COMMENT '接收人（sys_user.id）',
  type        VARCHAR(30)  NOT NULL COMMENT '通知类型：P3 只产生 TASK_PUBLISHED，取值只增不改（13 §E16）',
  title       VARCHAR(100) NOT NULL COMMENT '标题',
  content     VARCHAR(500) NOT NULL COMMENT '内容',
  is_read     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '已读标志：0 未读 / 1 已读（未读角标与全部已读，13 §E16）',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间；通知不可改，故无 updated_at',
  PRIMARY KEY (id),
  KEY idx_notification_receiver_read (receiver_id, is_read)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '站内通知（notify 最小版）';

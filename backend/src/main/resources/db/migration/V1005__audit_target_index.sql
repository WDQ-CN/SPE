-- V1005：audit_log 按目标查询索引（29 §3.5 详情审计摘要）。
-- 详情页按 target_type + target_id 取一条整改的全部动作，按时间正序展示；
-- 与查询同序 (target_type, target_id, created_at, id)，避免全表扫 + filesort。
-- 只增不改：应用后本文件冻结。

ALTER TABLE audit_log
  ADD KEY idx_audit_target (target_type, target_id, created_at, id);

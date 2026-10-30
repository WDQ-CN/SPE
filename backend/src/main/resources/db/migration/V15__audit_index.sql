-- M5 W5（01 SM5 / 22 §2 D8）：按 50k 行 EXPLAIN 探针（`22-w5-explain.log`）证明的两条索引。
-- 探针结论：默认序 `created_at DESC, id DESC` 走 idx_audit_created（backward index scan，20 行）；
-- 动作过滤走 idx_audit_action_created（ref + backward scan）；时间区间走 idx_audit_created（range）。
CREATE INDEX idx_audit_created ON audit_log (created_at);
CREATE INDEX idx_audit_action_created ON audit_log (action, created_at);

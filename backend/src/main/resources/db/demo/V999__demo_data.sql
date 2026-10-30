-- M5 W7（01 §3 演示数据 / 22 §2 D10）：演示模板 + 30 家供应商 + 7 个账号 + 两个已完成季度 + 一个进行中任务 + 演示预警。
-- 只在 `demo` profile 下加载（application-demo.yml 把 db/demo 加进 flyway.locations）；不碰任何既有模板/版本行。
-- ⚠️ 本文件一经应用即冻结（Flyway checksum）：调整演示数据须新开迁移或清卷（22 §5 R11）。
-- 账号口令：全部演示账号使用与种子管理员相同的演示口令（Admin@12345），仅用于本地演示。
SET @demo_template_count := (SELECT COUNT(*) FROM template WHERE name = '演示模板（M5）');

INSERT INTO template (name)
SELECT '演示模板（M5）' WHERE @demo_template_count = 0;
SET @demo_template_id := (SELECT id FROM template WHERE name = '演示模板（M5）' ORDER BY id LIMIT 1);

INSERT INTO template_version (template_id, version_no, status, published_at, version)
SELECT @demo_template_id, 1, 'PUBLISHED', NOW(3), 0
WHERE NOT EXISTS (
  SELECT 1 FROM template_version WHERE template_id = @demo_template_id AND version_no = 1
);
SET @demo_version_id := (
  SELECT id FROM template_version WHERE template_id = @demo_template_id AND version_no = 1
);

INSERT INTO dimension (template_version_id, name, weight, sort_no, suggested_dept, is_key, version)
SELECT @demo_version_id, d.name, d.weight, d.sort_no, d.dept, d.is_key, 0
FROM (
  SELECT '质量' AS name, 30.00 AS weight, 1 AS sort_no, 'QUALITY' AS dept, 1 AS is_key
  UNION ALL SELECT '交付', 25.00, 2, 'DELIVERY', 1
  UNION ALL SELECT '成本', 20.00, 3, 'PURCHASE', 0
  UNION ALL SELECT '服务', 25.00, 4, 'SERVICE', 0
) d
WHERE NOT EXISTS (
  SELECT 1 FROM dimension x WHERE x.template_version_id = @demo_version_id AND x.name = d.name
);

INSERT INTO indicator (dimension_id, template_version_id, name, code, weight, type, score_mode, standard, sort_no, version)
SELECT d.id, @demo_version_id, CONCAT(d.name, '演示指标'), CONCAT('DEMO_', d.id), 100.00, 'QUAL', NULL,
       '演示数据：1~5 档，5 为最好', 1, 0
FROM dimension d
WHERE d.template_version_id = @demo_version_id
  AND NOT EXISTS (
    SELECT 1 FROM indicator i WHERE i.dimension_id = d.id AND i.code = CONCAT('DEMO_', d.id)
  );

INSERT INTO sys_user (username, password_hash, name, role, department, status, version)
SELECT 'demo-manager1', '$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm', '演示采购经理一', 'MANAGER', 'PURCHASE', 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'demo-manager1');
INSERT INTO sys_user (username, password_hash, name, role, department, status, version)
SELECT 'demo-manager2', '$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm', '演示采购经理二', 'MANAGER', 'PURCHASE', 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'demo-manager2');
INSERT INTO sys_user (username, password_hash, name, role, department, status, version)
SELECT 'demo-eval-quality', '$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm', '演示质量评估人', 'EVALUATOR', 'QUALITY', 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'demo-eval-quality');
INSERT INTO sys_user (username, password_hash, name, role, department, status, version)
SELECT 'demo-eval-delivery', '$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm', '演示交付评估人', 'EVALUATOR', 'DELIVERY', 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'demo-eval-delivery');
INSERT INTO sys_user (username, password_hash, name, role, department, status, version)
SELECT 'demo-eval-cost', '$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm', '演示成本评估人', 'EVALUATOR', 'PURCHASE', 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'demo-eval-cost');
INSERT INTO sys_user (username, password_hash, name, role, department, status, version)
SELECT 'demo-eval-service', '$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm', '演示服务评估人', 'EVALUATOR', 'SERVICE', 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'demo-eval-service');
INSERT INTO sys_user (username, password_hash, name, role, department, status, version)
SELECT 'demo-viewer', '$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm', '演示只读用户', 'VIEWER', 'OTHER', 'ENABLED', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'demo-viewer');

INSERT INTO supplier (code, name, category, status, version)
WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 30)
SELECT CONCAT('DEMO-', LPAD(seq.n, 3, '0')),
       CONCAT('演示供应商', LPAD(seq.n, 2, '0')),
       ELT(1 + (seq.n % 6), '电子', '机械', '包装', '物流', '服务', '原材料'),
       'ENABLED', 0
FROM seq
WHERE NOT EXISTS (SELECT 1 FROM supplier s WHERE s.code = CONCAT('DEMO-', LPAD(seq.n, 3, '0')));

INSERT INTO eval_task (name, year, quarter, template_version_id, deadline, status, version)
SELECT '演示 2026 Q2', 2026, 2, @demo_version_id, '2026-06-30', 'COMPLETED', 0
WHERE NOT EXISTS (SELECT 1 FROM eval_task WHERE name = '演示 2026 Q2');
INSERT INTO eval_task (name, year, quarter, template_version_id, deadline, status, version)
SELECT '演示 2026 Q3', 2026, 3, @demo_version_id, '2026-09-30', 'COMPLETED', 0
WHERE NOT EXISTS (SELECT 1 FROM eval_task WHERE name = '演示 2026 Q3');
INSERT INTO eval_task (name, year, quarter, template_version_id, deadline, status, version)
SELECT '演示 2026 Q4（进行中）', 2026, 4, @demo_version_id, '2026-12-31', 'IN_PROGRESS', 0
WHERE NOT EXISTS (SELECT 1 FROM eval_task WHERE name = '演示 2026 Q4（进行中）');

INSERT INTO approval_node (task_id, approver_id, seq)
SELECT t.id, u.id, 1 FROM eval_task t JOIN sys_user u ON u.username = 'demo-manager1'
WHERE t.name IN ('演示 2026 Q2', '演示 2026 Q3', '演示 2026 Q4（进行中）')
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.task_id = t.id AND n.seq = 1);
INSERT INTO approval_node (task_id, approver_id, seq)
SELECT t.id, u.id, 2 FROM eval_task t JOIN sys_user u ON u.username = 'demo-manager2'
WHERE t.name IN ('演示 2026 Q2', '演示 2026 Q3', '演示 2026 Q4（进行中）')
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.task_id = t.id AND n.seq = 2);

INSERT INTO task_supplier (task_id, supplier_id, status, current_node, round, total_score, total_grade, version)
SELECT t.id, s.id, 'EFFECTIVE', 2, 1,
       60 + ((CAST(SUBSTRING(s.code, 6) AS UNSIGNED) * 7 + IF(t.quarter = 3, 4, 0)) % 40),
       CASE
         WHEN 60 + ((CAST(SUBSTRING(s.code, 6) AS UNSIGNED) * 7 + IF(t.quarter = 3, 4, 0)) % 40) >= 90 THEN 'A'
         WHEN 60 + ((CAST(SUBSTRING(s.code, 6) AS UNSIGNED) * 7 + IF(t.quarter = 3, 4, 0)) % 40) >= 80 THEN 'B'
         WHEN 60 + ((CAST(SUBSTRING(s.code, 6) AS UNSIGNED) * 7 + IF(t.quarter = 3, 4, 0)) % 40) >= 70 THEN 'C'
         ELSE 'D'
       END,
       0
FROM eval_task t JOIN supplier s ON s.code LIKE 'DEMO-%'
WHERE t.name IN ('演示 2026 Q2', '演示 2026 Q3')
  AND NOT EXISTS (SELECT 1 FROM task_supplier ts WHERE ts.task_id = t.id AND ts.supplier_id = s.id);

INSERT INTO task_supplier (task_id, supplier_id, status, current_node, round, version)
SELECT t.id, s.id, 'SCORING', 0, 1, 0
FROM eval_task t JOIN supplier s ON s.code IN ('DEMO-001','DEMO-002','DEMO-003','DEMO-004','DEMO-005',
                                                'DEMO-006','DEMO-007','DEMO-008','DEMO-009','DEMO-010')
WHERE t.name = '演示 2026 Q4（进行中）'
  AND NOT EXISTS (SELECT 1 FROM task_supplier ts WHERE ts.task_id = t.id AND ts.supplier_id = s.id);

INSERT INTO task_supplier_dimension_result (task_supplier_id, dimension_id, dimension_name, weight, score, grade)
SELECT ts.id, d.id, d.name, d.weight,
       LEAST(100, GREATEST(0, ts.total_score + CASE d.sort_no WHEN 1 THEN 3 WHEN 2 THEN -2 WHEN 3 THEN 5 ELSE 0 END)),
       CASE
         WHEN LEAST(100, GREATEST(0, ts.total_score + CASE d.sort_no WHEN 1 THEN 3 WHEN 2 THEN -2 WHEN 3 THEN 5 ELSE 0 END)) >= 90 THEN 'A'
         WHEN LEAST(100, GREATEST(0, ts.total_score + CASE d.sort_no WHEN 1 THEN 3 WHEN 2 THEN -2 WHEN 3 THEN 5 ELSE 0 END)) >= 80 THEN 'B'
         WHEN LEAST(100, GREATEST(0, ts.total_score + CASE d.sort_no WHEN 1 THEN 3 WHEN 2 THEN -2 WHEN 3 THEN 5 ELSE 0 END)) >= 70 THEN 'C'
         ELSE 'D'
       END
FROM task_supplier ts
JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
JOIN dimension d ON d.template_version_id = @demo_version_id
WHERE NOT EXISTS (
  SELECT 1 FROM task_supplier_dimension_result r
  WHERE r.task_supplier_id = ts.id AND r.dimension_id = d.id
);

INSERT INTO sheet (task_supplier_id, dimension_id, assignee_id, status, version)
SELECT ts.id, d.id,
       (SELECT u.id FROM sys_user u WHERE u.username = CASE d.sort_no
          WHEN 1 THEN 'demo-eval-quality'
          WHEN 2 THEN 'demo-eval-delivery'
          WHEN 3 THEN 'demo-eval-cost'
          ELSE 'demo-eval-service' END),
       IF(t.name = '演示 2026 Q4（进行中）', 'SCORING', 'SUBMITTED'), 0
FROM task_supplier ts
JOIN eval_task t ON t.id = ts.task_id
JOIN dimension d ON d.template_version_id = @demo_version_id
WHERE t.name IN ('演示 2026 Q2', '演示 2026 Q3', '演示 2026 Q4（进行中）')
  AND NOT EXISTS (SELECT 1 FROM sheet sh WHERE sh.task_supplier_id = ts.id AND sh.dimension_id = d.id);

INSERT INTO score (sheet_id, indicator_id, raw_value, score_value, source)
SELECT sh.id, i.id, 4, dr.score, 'MANUAL'
FROM sheet sh
JOIN indicator i ON i.dimension_id = sh.dimension_id
JOIN task_supplier_dimension_result dr
  ON dr.task_supplier_id = sh.task_supplier_id AND dr.dimension_id = sh.dimension_id
JOIN eval_task t ON t.id = (SELECT ts.task_id FROM task_supplier ts WHERE ts.id = sh.task_supplier_id)
WHERE t.name IN ('演示 2026 Q2', '演示 2026 Q3')
  AND NOT EXISTS (SELECT 1 FROM score s WHERE s.sheet_id = sh.id AND s.indicator_id = i.id);

INSERT INTO approval_record (task_supplier_id, round, seq, approver_id, action, comment)
SELECT ts.id, 1, n.seq, n.approver_id, 'APPROVE', '演示审批通过'
FROM task_supplier ts
JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
JOIN approval_node n ON n.task_id = ts.task_id
WHERE NOT EXISTS (
  SELECT 1 FROM approval_record r
  WHERE r.task_supplier_id = ts.id AND r.round = 1 AND r.seq = n.seq
);

INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content, status, handled_by, handled_comment, handled_at)
SELECT ts.id, ts.supplier_id, 2026, 3, 'TOTAL_LOW', '总分低于阈值',
       CONCAT('供应商 ', s.name, ' 的 2026 Q3 总分 ', ts.total_score, ' 低于阈值 70'),
       'UNREAD', NULL, NULL, NULL
FROM task_supplier ts JOIN eval_task t ON t.id = ts.task_id AND t.name = '演示 2026 Q3'
JOIN supplier s ON s.id = ts.supplier_id
WHERE s.code = 'DEMO-001'
  AND NOT EXISTS (SELECT 1 FROM alert a WHERE a.task_supplier_id = ts.id AND a.type = 'TOTAL_LOW');

INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content, status)
SELECT ts.id, ts.supplier_id, 2026, 3, 'KEY_DIMENSION_LOW', '关键维度低于阈值',
       CONCAT('供应商 ', s.name, ' 的 2026 Q3 关键维度「成本」低于阈值 70'),
       'UNREAD'
FROM task_supplier ts JOIN eval_task t ON t.id = ts.task_id AND t.name = '演示 2026 Q3'
JOIN supplier s ON s.id = ts.supplier_id
WHERE s.code = 'DEMO-002'
  AND NOT EXISTS (SELECT 1 FROM alert a WHERE a.task_supplier_id = ts.id AND a.type = 'KEY_DIMENSION_LOW');

INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content, status)
SELECT ts.id, ts.supplier_id, 2026, 3, 'TREND_DECLINE', '总分连续下降',
       CONCAT('供应商 ', s.name, ' 最近 3 个连续季度总分持续下降'),
       'UNREAD'
FROM task_supplier ts JOIN eval_task t ON t.id = ts.task_id AND t.name = '演示 2026 Q3'
JOIN supplier s ON s.id = ts.supplier_id
WHERE s.code = 'DEMO-003'
  AND NOT EXISTS (SELECT 1 FROM alert a WHERE a.task_supplier_id = ts.id AND a.type = 'TREND_DECLINE');

INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content, status, handled_by, handled_comment, handled_at)
SELECT ts.id, ts.supplier_id, 2026, 2, 'TOTAL_LOW', '总分低于阈值',
       CONCAT('供应商 ', s.name, ' 的 2026 Q2 总分 ', ts.total_score, ' 低于阈值 70'),
       'HANDLED',
       (SELECT u.id FROM sys_user u WHERE u.username = 'demo-manager1'),
       '已约谈供应商，等待整改', NOW(3)
FROM task_supplier ts JOIN eval_task t ON t.id = ts.task_id AND t.name = '演示 2026 Q2'
JOIN supplier s ON s.id = ts.supplier_id
WHERE s.code = 'DEMO-004'
  AND NOT EXISTS (SELECT 1 FROM alert a WHERE a.task_supplier_id = ts.id AND a.type = 'TOTAL_LOW');

INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content, status, superseded_by)
SELECT ts.id, ts.supplier_id, 2026, 3, 'TOTAL_LOW', '总分低于阈值',
       CONCAT('供应商 ', s.name, ' 的 2026 Q3 旧预警（已被重算取代）'),
       'SUPERSEDED', NULL
FROM task_supplier ts JOIN eval_task t ON t.id = ts.task_id AND t.name = '演示 2026 Q3'
JOIN supplier s ON s.id = ts.supplier_id
WHERE s.code = 'DEMO-005'
  AND NOT EXISTS (
    SELECT 1 FROM alert a WHERE a.task_supplier_id = ts.id AND a.status = 'SUPERSEDED'
  );

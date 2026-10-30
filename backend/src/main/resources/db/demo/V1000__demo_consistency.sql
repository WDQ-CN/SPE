-- M5 五轮审查修复（03 §44 的 M5R-02）：V999 的演示预警与分数不自洽。
-- V999 已应用（Flyway checksum 冻结），故不改 V999，而在其后新增本迁移做数据修正：
--   1. 每个演示维度只有一个指标，按 raw_value=1~5 → score_value=raw*20 重算；
--   2. 维度快照从指标分回算，总分从维度加权和回算（不再用「总分+固定偏移」的假快照）；
--   3. 重算后只有 DEMO-001 Q2（总分 59）与 DEMO-004 Q2（总分 62，仅总分低）触发预警；
--      重建 5 条演示预警，全部与规则/分数一致（含 1 条已处理、1 条已取代样例）。
-- 只动 DEMO-* 供应商与「演示 2026 Q2/Q3」；不使用临时表（应用账号 spe_app 没有
-- CREATE TEMPORARY TABLES 权限，使用派生表即可）。
-- 注意：V999 冻结后若再调整演示数据，应继续新增 demo 迁移，而不是回改 V999/V1000。

-- 指标分 = 原始值 × 20（演示模板每维一个定性指标，raw 1~5 的既有换算口径）。
UPDATE score sc
JOIN sheet sh ON sh.id = sc.sheet_id
JOIN (
  SELECT ts.id AS task_supplier_id, d.id AS dimension_id,
         CASE
           -- DEMO-001 Q2：质量 3、交付 2、成本 4、服务 3 ⇒ 维度 60/40/80/60，总分 59；
           -- 关键维度质量/交付均 < 70，命中 TOTAL + 两条 KEY。
           WHEN s.code = 'DEMO-001' AND t.quarter = 2 THEN
             CASE d.sort_no WHEN 1 THEN 3 WHEN 2 THEN 2 WHEN 3 THEN 4 ELSE 3 END
           -- DEMO-001 Q3：全 4 ⇒ 维度 80、总分 80，不触发。
           WHEN s.code = 'DEMO-001' AND t.quarter = 3 THEN 4
           -- DEMO-004 Q2：质量/交付 4、成本/服务 2 ⇒ 80/80/40/40，总分 62；
           -- 关键维度都 ≥ 70，只命中 TOTAL。
           WHEN s.code = 'DEMO-004' AND t.quarter = 2 THEN
             CASE d.sort_no WHEN 1 THEN 4 WHEN 2 THEN 4 WHEN 3 THEN 2 ELSE 2 END
           -- DEMO-005 Q3：全 5 ⇒ 100，仅保留一条 SUPERSEDED 历史样例。
           WHEN s.code = 'DEMO-005' AND t.quarter = 3 THEN 5
           -- 其余演示行：4/5 交替，全部 ≥ 70，保证不会凭空产生未种子的预警。
           ELSE 4 + (CAST(SUBSTRING(s.code, 6) AS UNSIGNED) % 2)
         END AS raw_value
  FROM task_supplier ts
  JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
  JOIN supplier s ON s.id = ts.supplier_id AND s.code LIKE 'DEMO-%'
  JOIN dimension d ON d.template_version_id = t.template_version_id
) raw ON raw.task_supplier_id = sh.task_supplier_id AND raw.dimension_id = sh.dimension_id
SET sc.raw_value = raw.raw_value,
    sc.score_value = raw.raw_value * 20;

-- 维度快照从指标分回算；等级仍按出厂阈值 A≥90/B≥80/C≥70/D<70。
UPDATE task_supplier_dimension_result r
JOIN (
  SELECT ts.id AS task_supplier_id, d.id AS dimension_id,
         (CASE
            WHEN s.code = 'DEMO-001' AND t.quarter = 2 THEN
              CASE d.sort_no WHEN 1 THEN 3 WHEN 2 THEN 2 WHEN 3 THEN 4 ELSE 3 END
            WHEN s.code = 'DEMO-001' AND t.quarter = 3 THEN 4
            WHEN s.code = 'DEMO-004' AND t.quarter = 2 THEN
              CASE d.sort_no WHEN 1 THEN 4 WHEN 2 THEN 4 WHEN 3 THEN 2 ELSE 2 END
            WHEN s.code = 'DEMO-005' AND t.quarter = 3 THEN 5
            ELSE 4 + (CAST(SUBSTRING(s.code, 6) AS UNSIGNED) % 2)
          END) * 20 AS score
  FROM task_supplier ts
  JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
  JOIN supplier s ON s.id = ts.supplier_id AND s.code LIKE 'DEMO-%'
  JOIN dimension d ON d.template_version_id = t.template_version_id
) raw ON raw.task_supplier_id = r.task_supplier_id AND raw.dimension_id = r.dimension_id
SET r.score = raw.score,
    r.grade = CASE
                WHEN raw.score >= 90 THEN 'A'
                WHEN raw.score >= 80 THEN 'B'
                WHEN raw.score >= 70 THEN 'C'
                ELSE 'D'
              END;

-- 总分 = 维度加权和（权重合计 100）；总分/等级同步重算。
UPDATE task_supplier ts
JOIN (
  SELECT r.task_supplier_id, ROUND(SUM(r.score * r.weight) / 100, 2) AS total
  FROM task_supplier_dimension_result r
  GROUP BY r.task_supplier_id
) x ON x.task_supplier_id = ts.id
JOIN eval_task t ON t.id = ts.task_id AND t.name IN ('演示 2026 Q2', '演示 2026 Q3')
SET ts.total_score = x.total,
    ts.total_grade = CASE
                       WHEN x.total >= 90 THEN 'A'
                       WHEN x.total >= 80 THEN 'B'
                       WHEN x.total >= 70 THEN 'C'
                       ELSE 'D'
                     END
WHERE ts.status = 'EFFECTIVE';

-- 只重建演示供应商的预警；e2e 自建供应商（M5-E2E-* 等）不受影响。
DELETE a FROM alert a JOIN supplier s ON s.id = a.supplier_id WHERE s.code LIKE 'DEMO-%';

INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content, status, handled_by, handled_comment, handled_at)
SELECT ts.id, ts.supplier_id, 2026, 2, 'TOTAL_LOW', '总分低于阈值',
       CONCAT('供应商 ', s.name, ' 的 2026 Q2 总分 ', ts.total_score, ' 低于阈值 70'),
       'HANDLED',
       (SELECT u.id FROM sys_user u WHERE u.username = 'demo-manager1'),
       '已约谈供应商，等待整改', NOW(3)
FROM task_supplier ts
JOIN eval_task t ON t.id = ts.task_id AND t.name = '演示 2026 Q2'
JOIN supplier s ON s.id = ts.supplier_id
WHERE s.code = 'DEMO-001';

INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content, status)
SELECT ts.id, ts.supplier_id, 2026, 2, 'KEY_DIMENSION_LOW', '关键维度低于阈值',
       CONCAT('供应商 ', s.name, ' 的 2026 Q2 关键维度「', d.name, '」得分 ', r.score, ' 低于阈值 70'),
       'UNREAD'
FROM task_supplier ts
JOIN eval_task t ON t.id = ts.task_id AND t.name = '演示 2026 Q2'
JOIN supplier s ON s.id = ts.supplier_id
JOIN task_supplier_dimension_result r ON r.task_supplier_id = ts.id
JOIN dimension d ON d.id = r.dimension_id AND d.is_key = 1
WHERE s.code = 'DEMO-001' AND r.score < 70;

INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content, status)
SELECT ts.id, ts.supplier_id, 2026, 2, 'TOTAL_LOW', '总分低于阈值',
       CONCAT('供应商 ', s.name, ' 的 2026 Q2 总分 ', ts.total_score, ' 低于阈值 70'),
       'UNREAD'
FROM task_supplier ts
JOIN eval_task t ON t.id = ts.task_id AND t.name = '演示 2026 Q2'
JOIN supplier s ON s.id = ts.supplier_id
WHERE s.code = 'DEMO-004';

INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content, status, superseded_by)
SELECT ts.id, ts.supplier_id, 2026, 3, 'TOTAL_LOW', '总分低于阈值',
       CONCAT('供应商 ', s.name, ' 的 2026 Q3 旧预警（已被重算取代）'),
       'SUPERSEDED', NULL
FROM task_supplier ts
JOIN eval_task t ON t.id = ts.task_id AND t.name = '演示 2026 Q3'
JOIN supplier s ON s.id = ts.supplier_id
WHERE s.code = 'DEMO-005';

-- V8：内置默认模板「季度绩效默认模板」（01 T5 + 附录 A，07 §3 纳入项）。脚本合入后不可修改（06 §1.3）。
--
-- 这是默认模板的**唯一权威源**（12 §E14）：将来 M6 的 V999__demo_data.sql 只能**引用**这里建的版本，
-- 不得重复插入一份——同一事实在两处各定义一次，必然漂移（CLAUDE「默认约定」第 3 条）。
--
-- 种入即 **PUBLISHED**（version_no=1）：07 §6 场景 2 的验收动作是「ADMIN 查看内置默认模板，微调权重后发布为新
-- 版本」，也就是说这份模板对任务是可选的，因此它必须是已发布状态。它的 `copied_from_version_id` 为空
-- （不是由哪个版本复制来的），`published_at` 取当前时间。
--
-- 字段取值的三处判断（12 §E15，逐条写在这里，免得后人以为是漏填）：
--   1. `unit`：附录 A 没有「单位」列（T3 有这个字段），按它的「说明」列推导——百分比→%，事故→次，审核→分，
--      定性→档。
--   2. `standard`（评分标准说明）：只在附录 A **真的给了换算规则或档位**时填。像「录入百分比」这类是
--      **输入说明**而不是评分标准，由 `unit`/`score_mode` 表达，故留空；`URGENT_RESPONSE` 是附录 A 唯一
--      给出完整 5 档文案的定性指标，照抄；其余 5 个定性指标附录 A 写「其余同构」而**没有给文案**，
--      一律留空并把「补文档」记在 12 §8 —— 自拟等于替需求编内容。
--   3. `is_key`：附录 A 未标注任何维度为关键维度，四个维度统一填 0（不替需求决定）。
--
-- 权重自检（应与 01 附录 A 逐项一致，W1 用 SQL 复核而不是看这个脚本）：
--   维度 40+30+20+10 = 100；质量 30+20+20+30 = 100；交付 50+30+20 = 100；成本 50+30+20 = 100；服务 40+30+30 = 100。

INSERT INTO template (name) VALUES ('季度绩效默认模板');
SET @tpl = LAST_INSERT_ID();

INSERT INTO template_version (template_id, version_no, status, copied_from_version_id, published_at)
VALUES (@tpl, 1, 'PUBLISHED', NULL, CURRENT_TIMESTAMP(3));
SET @ver = LAST_INSERT_ID();

-- ---------------------------------------------------------------- 质量（40%），建议评估部门：质量
INSERT INTO dimension (template_version_id, name, weight, sort_no, suggested_dept, is_key)
VALUES (@ver, '质量', 40.00, 1, '质量', 0);
SET @d1 = LAST_INSERT_ID();

INSERT INTO indicator (dimension_id, template_version_id, name, code, weight, type, score_mode, unit, target_value, standard, sort_no) VALUES
  (@d1, @ver, '来料批次合格率',   'IQC_PASS_RATE',     30.00, 'QUANT', 'RATIO',   '%',  NULL, NULL, 1),
  (@d1, @ver, '重大质量事故',     'QUALITY_ACCIDENT',  20.00, 'QUANT', 'DIRECT',  '次', NULL, '0 次=100，1 次=60，≥2 次=0', 2),
  (@d1, @ver, '来料检验及时率',   'IQC_ON_TIME_RATE',  20.00, 'QUANT', 'RATIO',   '%',  NULL, NULL, 3),
  (@d1, @ver, '质量体系审核得分', 'QMS_AUDIT_SCORE',   30.00, 'QUANT', 'DIRECT',  '分', NULL, '0~100', 4);

-- ---------------------------------------------------------------- 交付（30%），建议评估部门：交付物流
INSERT INTO dimension (template_version_id, name, weight, sort_no, suggested_dept, is_key)
VALUES (@ver, '交付', 30.00, 2, '交付物流', 0);
SET @d2 = LAST_INSERT_ID();

INSERT INTO indicator (dimension_id, template_version_id, name, code, weight, type, score_mode, unit, target_value, standard, sort_no) VALUES
  (@d2, @ver, '准时交付率',         'OTD_RATE',        50.00, 'QUANT', 'TARGET', '%',  98.0000, NULL, 1),
  (@d2, @ver, '订单满足率',         'FILL_RATE',       30.00, 'QUANT', 'RATIO',  '%',  NULL,    NULL, 2),
  (@d2, @ver, '紧急需求响应及时性', 'URGENT_RESPONSE', 20.00, 'QUAL',  NULL,     '档', NULL,
    '5=4 小时内响应并给出明确方案；4=8 小时内响应，方案基本可行；3=24 小时内响应，需多次沟通；2=72 小时内响应，方案敷衍；1=无响应或长期推诿', 3);

-- ---------------------------------------------------------------- 成本（20%），建议评估部门：采购
INSERT INTO dimension (template_version_id, name, weight, sort_no, suggested_dept, is_key)
VALUES (@ver, '成本', 20.00, 3, '采购', 0);
SET @d3 = LAST_INSERT_ID();

INSERT INTO indicator (dimension_id, template_version_id, name, code, weight, type, score_mode, unit, target_value, standard, sort_no) VALUES
  (@d3, @ver, '降本目标达成率', 'COST_DOWN_RATE',       50.00, 'QUANT', 'TARGET', '%',  3.0000, NULL, 1),
  (@d3, @ver, '报价竞争力',     'PRICE_COMPETITIVENESS', 30.00, 'QUAL',  NULL,    '档', NULL,   NULL, 2),
  (@d3, @ver, '付款条件配合度', 'PAYMENT_COOP',          20.00, 'QUAL',  NULL,    '档', NULL,   NULL, 3);

-- ---------------------------------------------------------------- 服务（10%），建议评估部门：服务
INSERT INTO dimension (template_version_id, name, weight, sort_no, suggested_dept, is_key)
VALUES (@ver, '服务', 10.00, 4, '服务', 0);
SET @d4 = LAST_INSERT_ID();

INSERT INTO indicator (dimension_id, template_version_id, name, code, weight, type, score_mode, unit, target_value, standard, sort_no) VALUES
  (@d4, @ver, '问题响应及时性',   'ISSUE_RESPONSE',    40.00, 'QUAL', NULL, '档', NULL, NULL, 1),
  (@d4, @ver, '异常处理配合度',   'ABNORMAL_COOP',     30.00, 'QUAL', NULL, '档', NULL, NULL, 2),
  (@d4, @ver, '对接人员稳定性',   'CONTACT_STABILITY', 30.00, 'QUAL', NULL, '档', NULL, NULL, 3);

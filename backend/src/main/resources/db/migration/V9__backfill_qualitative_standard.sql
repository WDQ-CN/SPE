-- V9：回填 5 个定性指标的档位文案（`indicator.standard`）。内容来自 `01 附录 A`（2026-09-26 用户批准的提议稿）。
--
-- 为什么是**新脚本**而不是改 V8：V8 已合入 ⇒ 按 06 §1.3「迁移合入即冻结」不可再改（改它会变 checksum，
-- 已部署的环境会直接启动失败）。V8 当时只能把附录 A 里**真的给了文案**的 `URGENT_RESPONSE` 填上，
-- 其余 5 个定性指标附录 A 写"其余同构"而没有内容 ⇒ 一律留空并把"补文档"记在 12 §8。现在内容补齐了。
--
-- ⚠️ **编号：本脚本是 V9**（不是 V11）。模板上下文按 06 的"按上下文分脚本"占 **V7/V8/V9** 连续段，
-- 评估流程建表顺延为 V10、通知为 V11（13 §E1 已同步）。**不要**再写成 V11 这类跳号：Flyway 默认拒绝
-- 乱序，一旦先把 V11 应用到持久库，之后补 V9/V10 就是 `FlywayValidateException: Detected resolved
-- migration not applied to database: 9` + **后端直接起不来**（03 §31.7.9 有这个实测记录）。
--
-- ⚠️ **锚点不靠模板名**（03 §31.7.10/§31.7.11）：`template.name` 既可被 `PUT /api/templates/{id}` 改掉、
-- 又没有唯一键（V7 有意不建）。按名匹配有两个坏下场——改名 ⇒ 静默 0 行（回填没发生却记 success=1）；
-- 出现同名模板 ⇒ 子查询 `return more than 1 row` ⇒ **迁移硬失败**（实测 ERROR 1242）。
-- 这里改用 **V8 亲手写入的指纹**（`URGENT_RESPONSE` 的 5 档文案）认定"这一版是 V8 种下的默认模板版本"：
--   · 改名不影响它；
--   · **复制出来的副本同样带这段文案** ⇒ "V8 那一版 + 它的副本"自然一起被覆盖（03 §31.7.11 的 P2-4：
--     只填 v1 会让"编辑默认模板"复用到的那份派生草稿永远没有文案）；
--   · 用户自建的模板不会有这段文案 ⇒ 不会被误伤。
--
-- ⚠️ **只填 `standard IS NULL` 的行**：不覆盖任何人已经写好的文案。这比"只改 v1"精确。
-- 幂等：第二次跑 0 行变更；锚点仍在 ⇒ 自检不报错。
--
-- 内容口径（与 01 §6.1a / 附录 A 一致）：
--   · 统一档位语义 5=标杆 / 4=良好 / 3=达标 / 2=待改进 / 1=不合格（×20 ⇒ 100/80/60/40/20）。
--   · `standard` 只存**档位短文案**（打分页 1~5 单选的标签），格式照 V8 的 `URGENT_RESPONSE`：
--     `5=…；4=…；3=…；2=…；1=…` 单列文本。判定细则/证据/边界写在 `01 附录 A`，**不进库**。
--   · `URGENT_RESPONSE` **本脚本不动**：它的文案 V8 已经种下（且是长格式），改它属于改既有内容，
--     本次批准的范围不含它。⇒ 打分页上它比另外 5 个略长，属**已知的外观不一致**（见 04-073）。

-- ---------------------------------------------------------------- 锚点自检（让"什么都没做"响起来）
-- 纯 SQL 没有 IF，`SIGNAL` 也只能在存储程序里条件化 ⇒ 用一张带 CHECK 的临时表表达
-- "当且仅当锚点缺失时报错"。实测 `SIGNAL` 在普通脚本里可用（ERROR 1644），但这里需要的是**条件**报错。
-- 锚点存在（正常情况，包括本脚本重跑）⇒ 插入 0 ⇒ 通过；一个都找不到 ⇒ 插入 1 ⇒ CHECK 失败 ⇒ 迁移失败。
-- ⚠️ 注意判据是**锚点是否存在**，不是"改了几行"——后者在重跑时合法地为 0，不能当错误。
CREATE TEMPORARY TABLE v9_anchor_guard (n INT NOT NULL CHECK (n = 0));
INSERT INTO v9_anchor_guard (n)
SELECT IF(COUNT(DISTINCT template_version_id) = 0, 1, 0)
  FROM indicator
 WHERE code = 'URGENT_RESPONSE'
   AND standard LIKE '5=4 小时内响应并给出明确方案%';

-- ---------------------------------------------------------------- 一次性回填 5 个指标
-- 派生表（而不是 `EXISTS` 子查询）是必须的：MySQL 不允许"UPDATE 某表、同时在子查询里读同一张表"
-- （实测 ERROR 1093: You can't specify target table 'i' for update in FROM clause）。
UPDATE indicator i
  JOIN template_version v ON v.id = i.template_version_id
  JOIN (
    SELECT DISTINCT template_version_id AS seed_version_id
      FROM indicator
     WHERE code = 'URGENT_RESPONSE'
       AND standard LIKE '5=4 小时内响应并给出明确方案%'
  ) seed ON seed.seed_version_id = v.id
SET i.standard = CASE i.code
  -- 成本：报价竞争力（30%）
  WHEN 'PRICE_COMPETITIVENESS' THEN '5=低于基准 5%+，成本透明；4=略低于基准，报价合理；3=与基准持平；2=高于基准 5~10%，需反复议价；1=高于基准 10%+，报价不透明'
  -- 成本：付款条件配合度（20%）
  WHEN 'PAYMENT_COOP'          THEN '5=≥90 天账期，无预付、无催款；4=60~90 天账期，无预付；3=30~60 天账期，催款可控；2=<30 天或要求预付，多次催款；1=坚持预付或货到即付'
  -- 服务：问题响应及时性（40%）
  WHEN 'ISSUE_RESPONSE'        THEN '5=≤2h 响应并给出可行方案；4=≤8h 响应，方案基本可行；3=≤24h 响应，方案需确认；2=≤48h 响应，方案流于形式；1=>48h 未响应或需多次催办'
  -- 服务：异常处理配合度（30%）
  WHEN 'ABNORMAL_COOP'         THEN '5=主动牵头根因分析，整改有效未复发；4=配合整改，报告完整，费用无争议；3=被动配合，报告需催交；2=推诿责任，整改不到位或复发；1=拒不配合整改或拒担责任'
  -- 服务：对接人员稳定性（30%）
  WHEN 'CONTACT_STABILITY'     THEN '5=期内未更换，交接有据；4=更换 1 次，交接顺畅；3=更换 2 次，需我方追问；2=更换 ≥3 次或信息断层；1=长期缺位，每次更换需重新磨合'
END
WHERE i.code IN ('PRICE_COMPETITIVENESS', 'PAYMENT_COOP', 'ISSUE_RESPONSE', 'ABNORMAL_COOP', 'CONTACT_STABILITY')
  AND i.standard IS NULL;

DROP TEMPORARY TABLE v9_anchor_guard;

-- V7：评估模板（01 T1~T5、02 §3、05 ADR-3）。脚本合入后不可修改（06 §1.3）。
--
-- 四表构成「模板 → 版本 → 维度 → 指标」。版本化靠 template_version 这一层承载（dimension/indicator 挂在
-- 版本下，而不是挂在 template 下），所以「发布即冻结」只需冻结版本本身（05 §5.4 的聚合）。
-- 唯一键与索引锚定 05 §8.2，与建表同脚本建齐（06 §1.3 禁止后补索引脚本与建表脚本分离）。
--
-- 三处刻意的设计（详见 12 §E2/E3/E11/E12）：
--   1. 权重用 DECIMAL(5,2)：发布要判「合计精确等于 100.00」，用浮点会让 33.33+33.33+33.34 出现判不等的假失败；
--      单行合法性另外用 CHECK 兜底（数值范围不是状态枚举，06 §4 的「不加 CHECK」只针对枚举）。
--   2. 三张子表都带 version 乐观锁列：发布靠状态守卫，但同一草稿的并发编辑状态相同、守卫无效，
--      维度与指标的改删必须各自有 version 才能守住（06 §4「按需」在本阶段判定为需要）。
--   3. indicator 冗余存 template_version_id，使「版本内编码唯一」由数据库唯一键兜底——
--      编码是 Excel 导入与开放 API 定位指标的依据（01 T3 / E3 / I1），靠「先查再插」在并发下会漏。

CREATE TABLE template (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name       VARCHAR(100) NOT NULL COMMENT '模板名称（01 未要求唯一，故不建唯一键；不替需求加约束，12 §E2）',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '评估模板';

CREATE TABLE template_version (
  id                     BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  template_id            BIGINT      NOT NULL COMMENT '所属模板',
  version_no             INT         NOT NULL COMMENT '版本号，模板内从 1 递增',
  status                 VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/PUBLISHED/ARCHIVED（P2 只用前两态，12 §E10）',
  copied_from_version_id BIGINT      NULL COMMENT '本草稿复制自哪个已发布版本；建档的 v1 为空。并发下靠它复用同一份草稿（12 §E5）',
  published_at           DATETIME(3) NULL COMMENT '发布时间；DRAFT 时为空',
  version                INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本：发布与草稿编辑都要守卫（06 §4、12 §E12）',
  created_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_template_version_no (template_id, version_no),
  KEY idx_template_version_draft (template_id, copied_from_version_id, status),
  KEY idx_template_version_status (template_id, status, version_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '模板版本（发布即冻结）';

CREATE TABLE dimension (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  template_version_id BIGINT       NOT NULL COMMENT '所属模板版本',
  name                VARCHAR(100) NOT NULL COMMENT '维度名称（如质量）',
  weight              DECIMAL(5,2) NOT NULL COMMENT '权重 0~100，版本内合计须为 100（发布时校验）',
  sort_no             INT          NOT NULL DEFAULT 0 COMMENT '排序号',
  suggested_dept      VARCHAR(50)  NULL COMMENT '建议评估部门：P3 发布任务时据此自动指派（01 J2）',
  is_key              TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否关键维度：供预警规则用（01 W1②）；附录 A 未标注，默认模板填 0（12 §E15）',
  version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本（12 §E2）',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_dimension_version (template_version_id),
  CONSTRAINT ck_dimension_weight CHECK (weight >= 0 AND weight <= 100)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '模板维度';

CREATE TABLE indicator (
  id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  dimension_id        BIGINT        NOT NULL COMMENT '所属维度',
  template_version_id BIGINT        NOT NULL COMMENT '冗余：所属版本。为使「版本内编码唯一」由唯一键兜底（12 §E11）',
  name                VARCHAR(100)  NOT NULL COMMENT '指标名称',
  code                VARCHAR(50)   NOT NULL COMMENT '指标编码：Excel 导入与开放 API 按它定位（01 T3）',
  weight              DECIMAL(5,2)  NOT NULL COMMENT '权重 0~100，维度内合计须为 100（发布时校验）',
  type                VARCHAR(20)   NOT NULL COMMENT '类型：QUANT 定量 / QUAL 定性',
  score_mode          VARCHAR(20)   NULL COMMENT '计分方式：DIRECT/RATIO/TARGET；type=QUAL 时为 NULL，策略由 type 决定（12 §E2）',
  unit                VARCHAR(20)   NULL COMMENT '单位（附录 A 未给该列，按「说明」列推导，12 §E15）',
  target_value        DECIMAL(12,4) NULL COMMENT '目标值：仅 TARGET 用，发布时须 > 0（01 §6.6）',
  standard            VARCHAR(500)  NULL COMMENT '评分标准说明（定性指标的档位文案；附录 A 只给了 URGENT_RESPONSE 一个，12 §E15）',
  sort_no             INT           NOT NULL DEFAULT 0 COMMENT '排序号',
  version             INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本（12 §E2）',
  created_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_indicator_version_code (template_version_id, code),
  KEY idx_indicator_dimension (dimension_id),
  CONSTRAINT ck_indicator_weight CHECK (weight >= 0 AND weight <= 100)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '模板指标';

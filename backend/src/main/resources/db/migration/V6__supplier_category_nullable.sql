-- V6：supplier.category 改为可空。
-- 依据：01 S1/S2 只要求「按编码/名称/分类搜索」与「新增/编辑/停用」，未要求分类必填；
-- V3 写成 NOT NULL 是替需求加了约束，应放开。V3 已合入不可修改（06 §1.3，Flyway 校验和层面也是硬约束），
-- 故以新脚本修正。
ALTER TABLE supplier
  MODIFY COLUMN category VARCHAR(50) NULL COMMENT '分类（自由文本，可空；P1 不做字典表）';

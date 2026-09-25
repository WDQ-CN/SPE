-- V3：供应商档案（01 S1/S2、02 §3）。脚本合入后不可修改（06 §1.3）。
-- 索引按 05 §8.2 只建编码唯一键；演示规模（数百行）不为搜索另加索引。
CREATE TABLE supplier (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code       VARCHAR(50)  NOT NULL COMMENT '供应商编码，业务唯一',
  name       VARCHAR(100) NOT NULL COMMENT '供应商名称',
  category   VARCHAR(50)  NOT NULL COMMENT '分类（自由文本，P1 不做字典表）',
  status     VARCHAR(20)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  version    INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本：主数据允许多角色编辑，故必备（06 §4）',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '供应商';

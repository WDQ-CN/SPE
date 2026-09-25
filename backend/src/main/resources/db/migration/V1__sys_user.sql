-- V1：系统用户表（09 D5）。脚本合入后不可修改，变更一律新脚本（06 §1.3）。
-- 唯一键与索引与建表同脚本创建，不拆分为后补脚本（06 §1.3）。
CREATE TABLE sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  username      VARCHAR(50)  NOT NULL COMMENT '登录账号',
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
  name          VARCHAR(50)  NOT NULL COMMENT '姓名',
  role          VARCHAR(20)  NOT NULL COMMENT '角色：ADMIN/MANAGER/EVALUATOR/VIEWER',
  department    VARCHAR(20)  NOT NULL COMMENT '部门 code：QUALITY/DELIVERY/PURCHASE/PRODUCTION/FINANCE/SERVICE/OTHER',
  status        VARCHAR(20)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本（06 §4）',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户';

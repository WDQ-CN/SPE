-- V5：种子管理员账号（09 §5 的「admin 账号种子数据（P1 迁移）」）。
-- 仅演示环境：口令为演示口令 Admin@12345，其 BCrypt 哈希由 spring-security-crypto 现场生成后写入
-- （不得手编哈希——手编出来的字符串无法通过校验，且只会在真正登录时才暴露）。
-- 正式环境部署前应改口令或移除本账号。部门 code 见 09 D5。
INSERT INTO sys_user (username, password_hash, name, role, department, status)
VALUES ('admin',
        '$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm',
        '系统管理员',
        'ADMIN',
        'OTHER',
        'ENABLED');

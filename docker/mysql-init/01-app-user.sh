#!/bin/bash
# 为应用创建**最小权限**数据库账号（03 §16 的 SEC-3）。
#
# 为什么：应用此前以 MySQL root 连库，等于「注入或凭据泄露的后果最大化」——root 能读写所有库、
# 能 GRANT、能读写文件。改为专用账号后，破坏面被限制在**本库**内，且无法提权。
#
# 权限清单（含 Flyway 需要的 DDL，因为迁移由应用启动时执行）：
#   DML：SELECT / INSERT / UPDATE / DELETE
#   DDL：CREATE / ALTER / DROP / INDEX / REFERENCES
# 刻意不给：GRANT OPTION、SUPER、FILE、PROCESS、CREATE USER，以及任何其它库的权限。
# 更严格的做法是「迁移用高权账号、运行用只读+限表账号」，那需要两套数据源与两段式启动，
# 对演示项目属过度设计；此处以「限库 + 无全局权限」为界（03 §16 已记这个折中）。
#
# 执行时机：MySQL 官方镜像**只在数据卷为空时**执行 /docker-entrypoint-initdb.d 下的脚本。
# 已有数据卷的环境不会自动建号，需要手工执行等价 SQL（命令见 11 §5.2）。
#
# 口令字符集：本脚本把口令内联进 SQL，故口令请只用字母、数字与 -_ 等不含引号的字符。
set -euo pipefail

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 未设置}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD 未设置}"
: "${DB_NAME:=spe}"

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
  CREATE USER IF NOT EXISTS 'spe_app'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';
  ALTER USER 'spe_app'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';
  GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
    ON \`${DB_NAME}\`.* TO 'spe_app'@'%';
  FLUSH PRIVILEGES;
EOSQL

echo "[init] 应用账号 spe_app 已就绪，权限范围：${DB_NAME}.*（无全局权限）"

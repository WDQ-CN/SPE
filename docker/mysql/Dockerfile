# MySQL 8.4 + Oracle Linux 安全补丁（03 §53 / 28 §11.3）
#
# 官方 mysql:8.4 镜像的 curl/libcurl 7.76.1-40.el9_8.8 在 Oracle errata
# ELSA-2026-92907（CVE-2026-8932，Moderate）中可修到 7.76.1-40.el9_8.9。
# 直接派生并升级这两个包；官方镜像刷新后这层自然变成 no-op。
FROM mysql:8.4
RUN microdnf update -y curl libcurl && microdnf clean all

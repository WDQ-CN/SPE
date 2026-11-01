# 构建阶段：Node 22 + pnpm（版本由 package.json 的 packageManager 字段钉定）
# 基础镜像的版本策略（03 §16 的 SEC-6）：**pin 到次版本线，不 pin 到具体补丁**——
#   既能持续拿到补丁级安全更新，又不会因为浮动到新次版本而悄悄换行为。
#   `node:22-alpine` = Node 22.x；`nginx:1.30-alpine` 曾是 `stable-alpine`（那会跨次版本，已收紧）。
#   升级基础镜像时：重建镜像 → 复跑 11 §5.3 的核对表 → 把新版本记进该节。
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

# 运行阶段：Nginx 托管静态资源并反代 /api（05 §4.2）
FROM nginx:1.30-alpine
# OSV 镜像核对（28 W4）：基础镜像自带的 curl/libcurl 8.22.0-r0 命中 24 条
# ALPINE-CVE-2026-*；Alpine 仓库已有修复版 8.23.0-r0，构建时显式升级并复扫。
RUN apk upgrade --no-cache curl libcurl
COPY nginx.conf /etc/nginx/conf.d/default.conf
# 安全响应头片段（含 CSP）：放在 snippets 而非 conf.d，避免被主配置的 `include *.conf` 当成 server 配置
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
# /api/ 与 /open/v1/ 共用的反代配置（03 §51 P2-4）
COPY api-proxy.conf /etc/nginx/snippets/api-proxy.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80

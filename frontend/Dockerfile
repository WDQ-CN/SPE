# 构建阶段：Node 22 + pnpm（版本由 package.json 的 packageManager 字段钉定）
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

# 运行阶段：Nginx 托管静态资源并反代 /api（05 §4.2）
FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80

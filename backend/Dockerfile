# 构建阶段：Maven + JDK 17（依赖单独成层，改代码不必重下依赖）
# 基础镜像的版本策略（03 §16 的 SEC-6）：pin 到次版本线，不 pin 到具体补丁——
#   既持续获得补丁级安全更新，又不会浮动到新次版本而悄悄换行为。
#   `maven:3.9-...` = Maven 3.9.x；`eclipse-temurin:17-jre-alpine` = JDK 17.x。
#   升级基础镜像时：重建镜像 → 复跑 11 §5.3 的核对表 → 把新版本记进该节。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# 质量门由本地 `mvn test` 承担（06 §5）；镜像构建跳过测试以缩短构建时间
RUN mvn -B -q -DskipTests package

# 运行阶段：仅 JRE（体积小、无编译工具链）
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S spe && adduser -S spe -G spe
WORKDIR /app
COPY --from=build /build/target/spe-backend-*.jar app.jar
USER spe
EXPOSE 8080
# 镜像内固定监听 8080，与 EXPOSE 和下面的 HEALTHCHECK 保持一致。
# 必须显式设置：application.yml 的默认端口是 18080（给宿主直跑用），裸跑 `docker run` 时若缺此项，
# 应用会监听 18080 而健康检查打 8080，容器将永远处于 unhealthy（实测确认）。
ENV SERVER_PORT=8080
# 探活走 /actuator/health（09 D6 中该路径 permitAll）；alpine 自带 busybox wget
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=12 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health || exit 1
# 堆上限按**容器内存限额的百分比**算（compose 里 backend 限 512m → 堆约 384m）：
# 不写死 -Xmx，是为了让同一镜像能跑在不同内存规格上（不设时 JVM 默认取 25%，与限额配合会偏小）；
# ExitOnOutOfMemoryError 让 OOM 快速失败，而不是让容器停在「还活着但已经病态」的状态（03 §16 的 PERF-5）。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

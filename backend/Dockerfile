# 构建阶段：Maven + JDK 17（依赖单独成层，改代码不必重下依赖）
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
# 探活走 /actuator/health（09 D6 中该路径 permitAll）；alpine 自带 busybox wget
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=12 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

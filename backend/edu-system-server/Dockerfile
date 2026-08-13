# ============ 构建阶段 ============
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 先拷贝 pom 以利用依赖缓存
COPY pom.xml .
COPY edu-pojo/pom.xml edu-pojo/pom.xml
COPY edu-common/pom.xml edu-common/pom.xml
COPY edu-api/pom.xml edu-api/pom.xml
RUN mvn dependency:go-offline -q || true

COPY edu-pojo edu-pojo
COPY edu-common edu-common
COPY edu-api edu-api
RUN mvn package -DskipTests -q

# ============ 运行阶段 ============
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/edu-api/target/edu-api-*.jar app.jar
ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

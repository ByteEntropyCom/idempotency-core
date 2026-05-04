# Stage 1: Build (Unchanged)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Production
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

ARG APP_VERSION
RUN addgroup --system spring && adduser --system spring --ingroup spring
USER spring:spring

COPY --from=build /app/target/idempotency-core-${APP_VERSION}.jar app.jar

# 1. Optimized JAVA_OPTS
# -XX:-MaxFDLimit: Common in containers to prevent startup delays
# -XX:+EnableDynamicAgentLoading: Adds support for future JDK strictness if needed
ENV JAVA_OPTS="-XX:+UseParallelGC -XX:MaxRAMPercentage=75.0 -XX:+EnableDynamicAgentLoading"

EXPOSE 8080

# 2. Exec form ENTRYPOINT (Better for SIGTERM handling)
ENTRYPOINT ["java", "-XX:+UseParallelGC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
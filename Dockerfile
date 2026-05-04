# Stage 1: Build the application
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Leverage Docker cache for dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build the jar
COPY src ./src
# Using -Drelease to ensure a clean, optimized build
RUN mvn clean package -DskipTests

# Stage 2: Create the production image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Security: Create and use non-root user
RUN addgroup --system spring && adduser --system spring --ingroup spring
USER spring:spring

# Copy the compiled jar - assuming standard artifact naming
# If your artifact name is dynamic, consider using: 
# COPY --from=build /app/target/idempotency-core-*.jar app.jar
COPY --from=build /app/target/*.jar app.jar

# Container-aware memory settings
ENV JAVA_OPTS="-XX:+UseParallelGC -XX:MaxRAMPercentage=75.0"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY pom.xml /app/pom.xml
COPY transaction-service/pom.xml /app/transaction-service/pom.xml

RUN apk add --no-cache maven && \
    mvn -pl transaction-service dependency:go-offline -B || true

COPY transaction-service/src /app/transaction-service/src
RUN mvn -pl transaction-service clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S paycore && adduser -S paycore -G paycore
USER paycore

COPY --from=builder /app/transaction-service/target/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

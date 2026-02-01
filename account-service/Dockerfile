# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
COPY ../pom.xml ../pom.xml
RUN ./mvnw -pl account-service -am package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S paycore && adduser -S paycore -G paycore

COPY --from=builder /build/target/*.jar app.jar
RUN chown paycore:paycore app.jar

USER paycore

EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]

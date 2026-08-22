# Stage 1: Build
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
COPY ../pom.xml ../pom.xml
RUN ./mvnw -pl eureka-server -am package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S paycore && adduser -S paycore -G paycore

COPY --from=builder /build/target/*.jar app.jar
RUN chown paycore:paycore app.jar

USER paycore

EXPOSE 8761

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8761/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]

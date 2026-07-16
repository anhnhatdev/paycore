FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S paycore && adduser -S paycore -G paycore
USER paycore

COPY target/audit-service-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8088

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8088/actuator/health || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

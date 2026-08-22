FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/notification-service-*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/fraud-service-*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

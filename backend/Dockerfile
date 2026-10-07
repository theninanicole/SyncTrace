FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY . .
RUN chmod +x mvnw && ./mvnw clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080

# Timestamps are zone-less LocalDateTime values; keep the server clock on the same
# UTC+8 wall time as local dev so stored times match and display correctly.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=GMT+08:00", "-jar", "/app/app.jar"]

# ---------- Build Stage ----------
FROM maven:3.9.7-eclipse-temurin-21 AS build
WORKDIR /app

# Copiamos SOLO el módulo users-service
COPY file-service/pom.xml file-service/
RUN mvn -f file-service/pom.xml dependency:go-offline -B

# Copiamos el código del módulo
COPY file-service/ file-service/
RUN mvn -f file-service/pom.xml -B package -DskipTests

# ---------- Runtime Stage ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copiamos el jar del módulo users-service
COPY --from=build /app/file-service/target/file-service-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--spring.profiles.active=prod"]

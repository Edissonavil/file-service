# ---------- Build Stage ----------
FROM maven:3.9.7-eclipse-temurin-21 AS build
WORKDIR /app

# Copia solo el pom.xml y descarga las dependencias (caché más eficiente)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copia el resto del código
COPY src ./src

# Construye el JAR, omitiendo los tests
RUN mvn -B package -DskipTests

# ---------- Runtime Stage ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]


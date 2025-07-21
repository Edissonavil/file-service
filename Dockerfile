# ---------- Build Stage ----------
FROM maven:3.9.7-eclipse-temurin-21 AS build
WORKDIR /app
RUN apk add --no-cache curl

# 1.  ⚡️ Pre-cache dependencias
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 2.  Copiamos SOLO el código de este micro-servicio
COPY src src
RUN mvn -B package -DskipTests

# ---------- Runtime Stage ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 3.  Copiamos el JAR generado
#    (reemplaza el prefijo por el artefactId real si fuese distinto)
COPY --from=build /app/target/*file*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--spring.profiles.active=prod"]

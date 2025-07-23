###########################
# 🔨 Build stage (Debian) #
###########################
FROM maven:3.9.7-eclipse-temurin-21 AS build
WORKDIR /app

# 1️⃣ Pre-cache dependencias
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 2️⃣ Copiamos SOLO el código del micro-servicio
COPY src src
RUN mvn -B package -DskipTests

############################
# 🚀 Runtime stage (Alpine) #
############################
FROM eclipse-temurin:21-jre-alpine        
WORKDIR /app

# 3️⃣ curl para health-check (solo runtime)
RUN apk add --no-cache curl

# 4️⃣ Copiamos el JAR generado
COPY --from=build /app/target/*file*.jar app.jar

# ✅ Copiamos la carpeta uploads del repositorio (solución al problema)
COPY uploads /app/uploads

EXPOSE 8080

# 5️⃣ Health-check interno del contenedor
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java","-jar","/app/app.jar","--spring.profiles.active=prod"]
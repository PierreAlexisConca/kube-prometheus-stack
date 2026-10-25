# ==========================================
# Etapa 1: Build de la aplicacion con Maven
# ==========================================
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# Copiar definicion de dependencias para aprovechar cache de capas Docker
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copiar codigo fuente y compilar artefacto
COPY src ./src
RUN mvn clean package -DskipTests -B

# ==========================================
# Etapa 2: Imagen final de ejecucion liviana
# ==========================================
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Crear usuario sin privilegios para buenas practicas de seguridad en K8s
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8081

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

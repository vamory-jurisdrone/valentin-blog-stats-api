# syntax=docker/dockerfile:1

# ---- Étape 1 : build Maven -------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dépendances d'abord : cette couche reste en cache tant que le pom ne change pas
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -DskipTests package \
    && cp target/stats-api-*.jar /workspace/app.jar

# ---- Étape 2 : image d'exécution légère ------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER app

# La JVM s'adapte à la mémoire allouée au conteneur
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 || exit 1

ENTRYPOINT ["java","-jar","/app/app.jar"]

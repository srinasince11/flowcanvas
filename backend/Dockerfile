# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM first so the dependency layer is cached independently of the
# source code — a code-only change does not re-download the world.
# .mvn/maven.config makes Maven retry patiently when Central rate-limits (HTTP 429).
COPY .mvn ./.mvn
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-root user: a container process should not be root by default.
RUN addgroup -S flowcanvas && adduser -S flowcanvas -G flowcanvas
USER flowcanvas

COPY --from=build /build/target/flowcanvas-backend-*.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

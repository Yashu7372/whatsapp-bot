# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy pom.xml and download dependencies first (layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

# Non-root user for Cloud Run security best practice
RUN groupadd -r appuser && useradd -r -g appuser appuser

WORKDIR /app

# Copy the built jar
COPY --from=builder /build/target/*.jar app.jar

# The OCI deployment mounts a named volume here for document-control uploads.
# Pre-create it with the runtime user's ownership so Docker copies the correct
# permissions into a newly-created volume.
RUN mkdir -p /data/uploads && chown -R appuser:appuser /app /data

# Cloud Run listens on $PORT (default 8080)
ENV SERVER_PORT=8080

USER appuser

EXPOSE 8080

# Use virtual threads (Java 21) + Cloud Run-friendly startup
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

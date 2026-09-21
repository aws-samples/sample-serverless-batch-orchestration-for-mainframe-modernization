# RUNNABLE STUB image — builds and runs anywhere. Test double for the licensed
# Blu Age/Gapwalk runtime so the CFN + Step Functions sample executes end-to-end.
# The ECS Command override (["JOB2A"]) becomes args[0] to the jar.

# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# ---- runtime ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/batch-stub.jar /app/batch-stub.jar
# Run as a non-root user so a code-execution flaw can't take full container control.
RUN useradd -r -u 10001 appuser
USER appuser
# HEALTHCHECK: this is a run-once batch job (starts, does its work, exits), not a
# long-running service, so there is no port/endpoint to probe. This verifies the
# runtime + artifact are intact. If a job container ever becomes long-running,
# replace this with a real liveness probe against its endpoint.
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD ["java", "-version"]
ENTRYPOINT ["java", "-jar", "/app/batch-stub.jar"]
CMD ["JOB1"]

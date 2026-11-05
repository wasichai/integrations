# syntax=docker/dockerfile:1
# the example app's image (ADR-0028). build from the repository root:
#   docker build --secret id=packages_token,env=WASICHAI_PACKAGES_TOKEN -f integrations-app/Dockerfile -t wasichai-integrations-app .
# the token (read:packages, for the wasichai-* libraries) is a BuildKit secret: mounted for one RUN, never in a layer

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
# the gradle home is a cache mount: dependencies survive between builds, and it never lands in the image
RUN --mount=type=secret,id=packages_token,required=true \
    --mount=type=cache,target=/root/.gradle \
    WASICHAI_PACKAGES_TOKEN="$(cat /run/secrets/packages_token)" \
    ./gradlew --no-daemon --console=plain :integrations-app:bootJar \
    && cp integrations-app/build/libs/integrations-app.jar /app.jar

FROM eclipse-temurin:25-jre
# curl for the healthcheck only
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=root:root /app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
# the heap follows the container's memory limit; an OutOfMemoryError ends the process so the orchestrator restarts it.
# the java launcher reads JDK_JAVA_OPTIONS: override it, or add flags with JAVA_TOOL_OPTIONS, at run time
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
# /actuator/health is public (wasichai's security chain) and includes the database
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health" > /dev/null || exit 1
# exec form: java is pid 1 and gets SIGTERM itself, so server.shutdown=graceful drains in-flight requests
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM scratch AS gradle-seed

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /source
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/main/ src/main/
COPY eval/docker/build-agent.sh /opt/build-agent.sh
ARG GRADLE_BUILD_OFFLINE=false
RUN --mount=type=cache,id=coding-agent-eval-gradle,target=/root/.gradle,sharing=locked \
    --mount=type=bind,from=gradle-seed,target=/opt/gradle-seed \
    sh /opt/build-agent.sh

FROM eclipse-temurin:17-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends python3 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/junit \
    && curl --fail --location --retry 3 \
       https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.0/junit-platform-console-standalone-1.10.0.jar \
       -o /opt/junit/console.jar
COPY --from=build /source/build/install/CodingAgentDemo /opt/agent
COPY eval/config.yaml /opt/eval/config.yaml
COPY eval/config-files.yaml /opt/eval/config-files.yaml
ENV CODING_AGENT_EVAL_CONTAINER=1 EVAL_CONFIG=/opt/eval/config.yaml
ENV CODING_AGENT_LOG_DIR=/logs/agent/java-logs
WORKDIR /workspace

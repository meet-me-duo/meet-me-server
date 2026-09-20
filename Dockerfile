FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies >/dev/null

COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-alpine AS runtime

RUN apk add --no-cache curl \
    && addgroup -g 10001 -S meetme \
    && adduser -u 10001 -S -D -H -G meetme meetme

WORKDIR /app

COPY --from=build --chown=meetme:meetme /workspace/build/libs/*.jar /app/app.jar
COPY --chown=meetme:meetme deploy/container-entrypoint.sh /app/container-entrypoint.sh

RUN chmod 0755 /app/container-entrypoint.sh

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=4 \
    CMD curl --fail --silent --show-error http://127.0.0.1:9090/actuator/health >/dev/null || exit 1

ENTRYPOINT ["/app/container-entrypoint.sh"]

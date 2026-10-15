# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN --mount=type=secret,id=proxy_ca,required=false \
    set -eu; \
    if [ -s /run/secrets/proxy_ca ]; then \
        keytool -importcert -noprompt -trustcacerts \
            -alias finance-build-proxy-ca \
            -file /run/secrets/proxy_ca \
            -keystore /tmp/finance-build-truststore -storepass changeit; \
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/tmp/finance-build-truststore -Djavax.net.ssl.trustStorePassword=changeit"; \
    fi; \
    proxy_url="${https_proxy:-${HTTPS_PROXY:-${http_proxy:-${HTTP_PROXY:-}}}}"; \
    if [ -n "$proxy_url" ]; then \
        proxy_authority="${proxy_url#*://}"; \
        proxy_authority="${proxy_authority##*@}"; \
        proxy_host="${proxy_authority%%:*}"; \
        proxy_port="${proxy_authority##*:}"; \
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttps.proxyHost=$proxy_host -Dhttps.proxyPort=$proxy_port -Dhttp.proxyHost=$proxy_host -Dhttp.proxyPort=$proxy_port"; \
    fi; \
    chmod +x ./gradlew; \
    ./gradlew --no-daemon dependencies; \
    rm -f /tmp/finance-build-truststore

COPY src ./src
RUN --mount=type=secret,id=proxy_ca,required=false \
    set -eu; \
    if [ -s /run/secrets/proxy_ca ]; then \
        keytool -importcert -noprompt -trustcacerts \
            -alias finance-build-proxy-ca \
            -file /run/secrets/proxy_ca \
            -keystore /tmp/finance-build-truststore -storepass changeit; \
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/tmp/finance-build-truststore -Djavax.net.ssl.trustStorePassword=changeit"; \
    fi; \
    proxy_url="${https_proxy:-${HTTPS_PROXY:-${http_proxy:-${HTTP_PROXY:-}}}}"; \
    if [ -n "$proxy_url" ]; then \
        proxy_authority="${proxy_url#*://}"; \
        proxy_authority="${proxy_authority##*@}"; \
        proxy_host="${proxy_authority%%:*}"; \
        proxy_port="${proxy_authority##*:}"; \
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttps.proxyHost=$proxy_host -Dhttps.proxyPort=$proxy_port -Dhttp.proxyHost=$proxy_host -Dhttp.proxyPort=$proxy_port"; \
    fi; \
    ./gradlew --no-daemon bootJar; \
    rm -f /tmp/finance-build-truststore

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app \
    && adduser -S -G app app

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=55.0 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError"

USER app:app
EXPOSE 8080
STOPSIGNAL SIGTERM

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
    CMD wget -Y off -q -O - http://127.0.0.1:8080/actuator/health/readiness | grep -q '"status":"UP"'

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

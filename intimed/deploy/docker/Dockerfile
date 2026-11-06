# IntiMed platform image (linux/amd64 for AKS, linux/arm64 for Apple silicon). Build the jar first: ./mvnw package
# Hardening: non-root UID 10001, no shell login, read-only root filesystem at runtime (Helm sets it), only the JRE.
ARG BASE=eclipse-temurin:21.0.8_9-jre-noble
FROM ${BASE}
LABEL org.opencontainers.image.title="IntiMed platform" org.opencontainers.image.vendor="IntiMed" org.opencontainers.image.licenses="Proprietary" \
      org.opencontainers.image.source="https://github.com/9pgvzkjvn4-cmd/WellnessZ" org.opencontainers.image.version="2.1.0"
RUN groupadd --system --gid 10001 intimed && useradd --system --uid 10001 --gid intimed --home-dir /data --shell /usr/sbin/nologin intimed \
    && mkdir -p /data && chown intimed:intimed /data \
    && apt-get update && apt-get -y upgrade && apt-get clean && rm -rf /var/lib/apt/lists/*
COPY --chown=root:root intimed-platform/target/intimed-platform.jar /opt/intimed/intimed-platform.jar
USER 10001:10001
ENV INTIMED_HOME=/data INTIMED_BIND=0.0.0.0 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 9443 8443 2575 9444
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s CMD ["java", "-Xmx64m", "-jar", "/opt/intimed/intimed-platform.jar", "sdk", "smoke", "127.0.0.1"]
ENTRYPOINT ["java", "-jar", "/opt/intimed/intimed-platform.jar"]

# IntiMed platform image (multi-arch: linux/arm64 for Apple silicon, linux/amd64). Build the jar first: ./mvnw package
FROM eclipse-temurin:21.0.8_9-jre-noble
LABEL org.opencontainers.image.title="IntiMed platform" org.opencontainers.image.vendor="IntiMed" org.opencontainers.image.licenses="Proprietary"
RUN groupadd --system --gid 10001 intimed && useradd --system --uid 10001 --gid intimed --home-dir /data --shell /usr/sbin/nologin intimed \
    && mkdir -p /data && chown intimed:intimed /data
COPY --chown=root:root intimed-platform/target/intimed-platform.jar /opt/intimed/intimed-platform.jar
USER 10001:10001
ENV INTIMED_HOME=/data INTIMED_BIND=0.0.0.0 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 9443 8443 2575
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD ["java", "-cp", "/opt/intimed/intimed-platform.jar", "-version"]
ENTRYPOINT ["java", "-jar", "/opt/intimed/intimed-platform.jar"]

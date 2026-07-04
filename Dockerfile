# syntax=docker/dockerfile:1.7
# ============== Stage 1: build ==============
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp package -DskipTests \
 && cp target/acervo-*.jar /workspace/app.jar

# ============== Stage 2: runtime ==============
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system acervo \
 && useradd --system --gid acervo --home /opt/acervo --shell /usr/sbin/nologin acervo \
 && mkdir -p /opt/acervo/data/uploads \
 && chown -R acervo:acervo /opt/acervo

WORKDIR /opt/acervo
COPY --from=build --chown=acervo:acervo /workspace/app.jar app.jar

USER acervo
EXPOSE 8080

ENV STORAGE_DIR=/opt/acervo/data/uploads \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /opt/acervo/app.jar"]

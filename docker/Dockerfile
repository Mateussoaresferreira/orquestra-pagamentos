FROM eclipse-temurin:25-jre-alpine@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682

RUN addgroup -S -g 10001 orquestrapay \
    && adduser -S -D -H -u 10001 -G orquestrapay orquestrapay

WORKDIR /aplicacao

ARG CAMINHO_ARTEFATO
COPY --chown=orquestrapay:orquestrapay ${CAMINHO_ARTEFATO} aplicacao.jar

USER 10001:10001

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=300s --retries=12 \
    CMD wget -q -T 3 -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-XX:InitialRAMPercentage=20.0", "-jar", "aplicacao.jar"]

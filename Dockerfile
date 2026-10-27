# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY fixflow-contracts/pom.xml fixflow-contracts/pom.xml
COPY fixflow-backend/pom.xml fixflow-backend/pom.xml
COPY fixflow-desktop/pom.xml fixflow-desktop/pom.xml
COPY fixflow-contracts/src/ fixflow-contracts/src/
COPY fixflow-backend/src/ fixflow-backend/src/
COPY docker/Healthcheck.java /tmp/Healthcheck.java
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl fixflow-backend -am -DskipTests package \
    && javac --release 21 -d /health /tmp/Healthcheck.java

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /opt/fixflow
COPY --from=build /workspace/fixflow-backend/target/fixflow-backend-0.1.0-SNAPSHOT.jar app.jar
COPY --from=build /health/ health/
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=5s --start-period=60s --retries=30 \
    CMD ["java", "-cp", "/opt/fixflow/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/fixflow/app.jar"]

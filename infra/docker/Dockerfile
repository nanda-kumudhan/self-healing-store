FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
ARG MODULE
RUN case "$MODULE" in coordinator|storage-node) ;; *) exit 2 ;; esac && \
    mvn -B -ntp -pl "$MODULE" -am package -DskipTests && \
    cp "$MODULE/target/$MODULE-0.1.0-SNAPSHOT.jar" /app.jar

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 store && useradd --uid 10001 --gid store --no-create-home store && \
    mkdir -p /data/shards && chown -R store:store /data
WORKDIR /app
COPY --from=build --chown=store:store /app.jar /app/app.jar
USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]

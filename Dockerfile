FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 drivelog && useradd --uid 10001 --gid drivelog --no-create-home drivelog
WORKDIR /app
COPY --chown=10001:10001 target/drivelog-api.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

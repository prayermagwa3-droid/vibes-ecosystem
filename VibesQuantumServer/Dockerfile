# Render supports Docker deployments for JVM applications.
# Build toolchain is pinned to the wrapper's Gradle and Java versions.
FROM gradle:9.6.1-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon clean installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV HOST=0.0.0.0
ENV PORT=10000
COPY --from=build /workspace/build/install/VibesQuantumServer/ ./
EXPOSE 10000
ENTRYPOINT ["/app/bin/VibesQuantumServer"]

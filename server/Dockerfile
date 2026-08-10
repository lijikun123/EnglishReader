FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src

COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon clean installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 kreader
WORKDIR /app
COPY --from=build /src/build/install/kreader-sync-server ./
RUN chown -R kreader:kreader /app
USER kreader
EXPOSE 8080
ENTRYPOINT ["/app/bin/kreader-sync-server"]

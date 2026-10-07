# Servidor do Apex. Construir:  docker build -t apex-server .   Rodar: docker run -p 8080:8080 --env-file server/.env apex-server
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ENV APEX_SERVER_ONLY=true
RUN ./gradlew :server:installDist --no-daemon -x test -Dorg.gradle.jvmargs="-Xmx1g -Dfile.encoding=UTF-8" -Dkotlin.daemon.jvm.options="-Xmx768m"

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home apex
COPY --from=build /src/server/build/install/server /app
USER apex
ENV PORT=8080
EXPOSE 8080
CMD ["/app/bin/server"]

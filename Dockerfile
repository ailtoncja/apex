# Servidor do Apex. Construir:  docker build -t apex-server .   Rodar: docker run -p 8080:8080 --env-file server/.env apex-server
# As imagens base ficam presas pelo digest (a tag "21-jdk"/"21-jre" sozinha pode mudar por baixo sem aviso). O Dependabot (ecossistema
# "docker") abre um pedido de atualização quando sai uma imagem nova; para atualizar à mão, pegue o "digest" em
# https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags/21-jre (e 21-jdk).
FROM eclipse-temurin:21-jdk@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d AS build
WORKDIR /src
COPY . .
ENV APEX_SERVER_ONLY=true
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon -x test -Dorg.gradle.jvmargs="-Xmx1g -Dfile.encoding=UTF-8" -Dkotlin.daemon.jvm.options="-Xmx768m"

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
RUN useradd --system --create-home apex
COPY --from=build /src/server/build/install/server /app
USER apex
ENV PORT=8080
# No container o servidor precisa ouvir em todas as interfaces (Render e Fly chegam por fora); fora dele o padrão é só 127.0.0.1.
ENV BIND_HOST=0.0.0.0
EXPOSE 8080
CMD ["/app/bin/server"]

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY build/libs/netplayer-server.jar /app/netplayer-server.jar

EXPOSE 25566
ENTRYPOINT ["java", "-jar", "/app/netplayer-server.jar", "--port", "25566"]

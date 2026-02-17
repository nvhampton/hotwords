# Stage 1: Build
# Using the specific Jammy (Ubuntu) build of JDK 21
FROM gradle:8.5-jdk21-jammy AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
# Use --no-daemon to prevent background processes from hanging the Docker build
RUN gradle shadowJar --no-daemon

# Stage 2: Run
FROM eclipse-temurin:21-jre-jammy
EXPOSE 8080
RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/game-server.jar /app/game-server.jar
ENTRYPOINT ["java", "-jar", "/app/game-server.jar"]
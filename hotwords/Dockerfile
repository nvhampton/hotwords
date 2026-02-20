# Stage 1: Build
# Using the specific Jammy (Ubuntu) build of JDK 21
FROM gradle:8.5-jdk21-jammy AS build
WORKDIR /home/gradle/src

# Copy only build files first to cache dependencies
COPY --chown=gradle:gradle build.gradle.kts settings.gradle.kts ./
COPY --chown=gradle:gradle gradle ./gradle
RUN gradle dependencies --no-daemon --console=plain || true

# Then copy source and build
COPY --chown=gradle:gradle . .
RUN gradle shadowJar --no-daemon

# Stage 2: Run
FROM eclipse-temurin:21-jre-jammy
EXPOSE 8080
RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/game-server.jar /app/game-server.jar
ENTRYPOINT ["java", "-jar", "/app/game-server.jar"]
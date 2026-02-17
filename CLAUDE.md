# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Hotwords is a multiplayer word game server built with Kotlin and Ktor. Players connect via WebSocket to game rooms and compete to claim words.

## Build Commands

All commands should be run from the `hotwords/` subdirectory.

```bash
# Build the project
./gradlew build

# Run the server (starts on port 8080)
./gradlew run

# Build fat JAR for deployment
./gradlew shadowJar
# Output: build/libs/game-server.jar

# Build Docker image
docker build -t hotwords .
```

## Architecture

- **Ktor WebSocket Server**: Main entry point is `com.example.ApplicationKt.module()` in `src/main/kotlin/com/example/Application.kt`
- **Room-based multiplayer**: Players join rooms via WebSocket at `/game/{roomId}`. Each room maintains its own set of connected sessions.
- **Message protocol**: Uses kotlinx.serialization JSON. Messages have a `type` field (`NEW_WORD`, `CLAIM_VICTORY`, `ROUND_WON`) plus optional `word` and `player` fields.
- **Game state**: Rooms stored in `ConcurrentHashMap`, sessions in synchronized sets. Active word is shared across all rooms.

## Tech Stack

- Kotlin 1.9.22 / JVM 21
- Ktor 2.3.7 with Netty
- kotlinx.serialization for JSON
- Shadow plugin for fat JAR packaging

# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

Hotwords is a word/phrase guessing game with two modes:
- **Local Mode**: Hot potato style - pass one device, say phrases before timer runs out
- **Online Mode**: Distributed hot potato with Taboo-style mechanics - one describer, multiple guessers

## Build Commands

Run from the `hotwords/` subdirectory:

```bash
./gradlew build      # Build project
./gradlew run        # Run locally on port 8080
./gradlew shadowJar  # Build fat JAR for deployment
docker build -t hotwords .  # Build Docker image
```

## Architecture

### Frontend (`src/main/resources/static/index.html`)
- Single-file HTML/CSS/JS (~2500 lines)
- Web Speech API for voice recognition
- DeviceOrientation API for tilt-to-reveal (local mode)
- Keyboard shortcuts: Space (reveal), Enter (got it), Tab (skip)

### Backend (`src/main/kotlin/com/example/Application.kt`)
- Ktor WebSocket server
- Room-based multiplayer at `/game/{roomId}`
- Player tracking with 15s TTL heartbeat
- In-memory state with `ConcurrentHashMap`

## Key Features

### Local Mode
- Word progress: `Player: #### ### #####`
- Words reveal as detected in speech
- Skip = same player, new phrase (hot potato)
- Timer runs continuously, loser is whoever holds at end

### Online Mode (Distributed Hot Potato)
- **Roles**: One "hot" player (describer) + guessers
- **Describer**: Sees full phrase, gives verbal clues (Taboo-style)
- **Guessers**: See `####` pattern, words reveal as they guess correctly
- **Turn rotation**: Victory or skip rotates describer
- **Speech monitoring**: Describer is penalized for saying forbidden words
- **Player list**: Shows all connected players with role badges

## Message Types

### Client → Server
| Type | Purpose |
|------|---------|
| `SET_NAME` | Join room with player name |
| `HEARTBEAT` | Keep-alive (every 10s) |
| `WORD_MATCH` | Guesser matched a word |
| `CLAIM_VICTORY` | Full phrase matched |
| `SKIP_WORD` | Skip phrase (rotates turn) |
| `DESCRIBER_SLIP` | Describer said forbidden word |
| `DESCRIBER_FAIL` | Describer said entire phrase |

### Server → Client
| Type | Purpose |
|------|---------|
| `PLAYER_LIST` | Current players + hot index |
| `NEW_WORD` | Phrase + revealed state |
| `WORD_PROGRESS` | Updated revealed words |
| `ROUND_WON` | Victory notification |
| `WORD_SKIPPED` | Skip notification |
| `DESCRIBER_SLIPPED` | Penalty notification |
| `DESCRIBER_FAILED` | Full fail notification |

## Tech Stack

- Kotlin 1.9.22 / JVM 21
- Ktor 2.3.7 + Netty
- kotlinx.serialization
- Shadow plugin for fat JAR
- Docker + Caddy for deployment

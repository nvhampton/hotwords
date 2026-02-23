# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

Run from the `hotwords/` subdirectory:

```bash
./gradlew build      # Build project
./gradlew run        # Run locally on port 8080
./gradlew shadowJar  # Build fat JAR for deployment
```

There are no unit tests. The project has no test dependencies or `src/test/` directory.

## Versioning

Version is defined in `build.gradle.kts` (`version = "0.9.0"`) as the single source of truth. The `@@VERSION@@` placeholder in `index.html` is replaced at build time via Gradle's `processResources`. To bump the version, only edit `build.gradle.kts`. Follow semver: patch for bug fixes, minor for new features, major for breaking changes.

## Deploy

```bash
# One command: builds locally, SCPs to EC2, restarts services
bash deploy/build-and-deploy.sh
```

This builds the fat JAR, uploads it to EC2, and runs `deploy.sh` remotely which restarts the systemd service and Caddy reverse proxy. Requires the EC2 host IP as an argument (or set `HOTWORDS_SSH_KEY` env var for the key path). Production URL: `https://hotwords.xyz`

## Architecture

### Two-file application

**Backend** — `src/main/kotlin/com/example/Application.kt` (~700 lines)
- Ktor WebSocket server with room-based multiplayer at `/game/{roomId}`
- REST endpoints: `POST /api/scores` (submit round), `GET /api/leaderboard` (fetch rankings)
- All state in-memory via `ConcurrentHashMap` (rooms, players, scores) — no database
- Player lifecycle: heartbeat every 10s from client, server TTL cleanup every 5s (15s expiry)
- Serializable `GameMessage` data class with string `type` discriminator for all WebSocket messages

**Frontend** — `src/main/resources/static/index.html` (~3700 lines)
- Single-file vanilla HTML/CSS/JS, no framework
- Web Speech API for voice recognition (Chrome/Edge/Safari)
- DeviceOrientation API for tilt-to-reveal on mobile (local mode)
- Press-to-reveal as fallback/supplement (desktop and mobile)
- Keyboard shortcuts: Space (reveal), Enter (got it), Tab (skip), Escape (lobby)

### Game modes

**Local Mode** — Single device, pass-and-play hot potato
- Timer runs continuously, player holding device when timer expires loses
- Difficulty: easy (words reveal individually as spoken) vs hard (shows word count only, full phrase match)
- Orientation/press reveals phrase; flat/release hides it
- Got It → next player, new phrase. Skip → same player, new phrase.

**Online Mode** — WebSocket rooms, distributed hot potato
- One "hot" player (describer) sees phrase, gives verbal clues
- Guessers see `####` pattern, words reveal as matched via speech
- Victory or skip rotates the describer role
- Describer penalized for saying forbidden words

### State flow

Room state is isolated per `roomId`. The server tracks: active players (with heartbeat TTL), current hot player index, current phrase, and a boolean array of revealed words. The client manages its own speech recognition, orientation sensors, and local game timer independently.

### Key frontend patterns

- **Reveal system**: `orientationEnabled` (tilt) and `tapToRevealEnabled` (press) can coexist; `pressRevealing` flag prevents orientation handler from hiding during active press; `stopReveal` defers to orientation state when in tilt mode
- **Cooldowns**: Got It (400ms) and Skip (separate) prevent spam; both re-enabled on word reveal after passing period
- **Game summary overlay** (z-index 1050) stays behind leaderboard overlay (z-index 1100); dismissing leaderboard reveals summary underneath
- **`pendingLeaderboard` promise**: Round submitted immediately in `endGame()`, stored as promise; both Leaderboard button and any auto-show await the same promise

## Message Protocol

### Client → Server
`SET_NAME`, `HEARTBEAT`, `WORD_MATCH`, `CLAIM_VICTORY`, `SKIP_WORD`, `DESCRIBER_SLIP`, `DESCRIBER_FAIL`, `NEW_ROUND`

### Server → Client
`PLAYER_LIST`, `NEW_WORD`, `WORD_PROGRESS`, `ROUND_WON`, `WORD_SKIPPED`, `DESCRIBER_SLIPPED`, `DESCRIBER_FAILED`, `TIMER_SYNC`, `GAME_STARTED`

## Branching

Use feature branches for development. Only merge to `main` when ready to deploy. The deploy script builds from the current working directory, so only run it from `main`.

## Changelog

After every session involving significant changes (20+ minutes of work, new features, bug fixes, security changes, or architectural decisions), append a dated section to `ProjectChanges.md` documenting:
- What changed and why
- Any bugs encountered and how they were fixed
- Key learnings (numbered, continuing from the last entry)
- Decisions made and alternatives considered

This is the project's institutional memory. Keep entries concise but specific enough to avoid repeating mistakes.

## Tech Stack

- Kotlin 1.9.22 / JVM 21 / Ktor 2.3.7 + Netty
- kotlinx.serialization for JSON
- Shadow plugin 8.1.1 for fat JAR (output: `build/libs/game-server.jar`, ~16MB)
- Caddy for HTTPS termination (auto Let's Encrypt) + reverse proxy
- systemd for process management on EC2

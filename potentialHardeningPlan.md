Implement the following hardening changes across the codebase. Build and verify after each group but do NOT deploy.

**File: `hotwords/src/main/kotlin/com/example/Application.kt`**

1. **Input validation** — Truncate player names to 50 chars and room IDs to 50 chars at the point they're received. Use `.take(50)` on `received.player` in the SET_NAME handler and on `roomId` from URL params.

2. **WebSocket rate limiting** — Add a simple per-session rate limiter. Track message timestamps per session in a `ConcurrentHashMap<DefaultWebSocketServerSession, MutableList<Long>>`. Allow max 20 messages per second. If exceeded, close the connection. Check at the top of the message processing loop before any message handling.

3. **Room and resource limits** — Add constants: `MAX_ROOMS = 5000`, `MAX_PLAYERS_PER_ROOM = 50`, `MAX_ROUND_ENTRIES = 50000`. Check room count before creating new rooms (close connection with reason if exceeded). Check player count before adding to a room. Evict oldest round entries when cap is hit. In the existing cleanup coroutine, also remove rooms that have had zero players for over 1 hour (add a `lastActivityTime` field to `RoomState`, update it on any player message).

4. **Health endpoint** — Add `GET /health` returning `{"status":"ok","version":"0.9.0"}`. Read the version from `build.gradle.kts`'s project version or hardcode it for now.

**File: `hotwords/src/main/resources/static/index.html`**

5. **XSS fix** — Find all uses of `innerHTML` that interpolate player names or other user-controlled data. The `escapeHtml()` function already exists in the codebase. Wrap player names with it everywhere they're used in innerHTML. Search for `player.name` and `playerName` in innerHTML contexts specifically.

**File: `hotwords/deploy/README.md`**

6. **Remove hardcoded credentials** — Replace the real SSH key path `~/Downloads/mysecurekeypair.pem` with `<your-ssh-key.pem>` and any specific EC2 IP addresses with `<your-ec2-ip>`. Keep the command structure intact.

**File: `hotwords/build.gradle.kts`**

7. **processResources cache fix** — The `processResources` task that stamps `@@VERSION@@` into index.html can serve stale cached output when only the version changes. Add `outputs.upToDateWhen { false }` to the `processResources` task configuration to force re-stamping on every build.

**File: `CLAUDE.md`**

8. **Document branching pattern** — Add a short section after Deploy: "Use feature branches for development. Only merge to `main` when ready to deploy. The deploy script builds from the current working directory, so only run it from `main`."

After all changes, run `./gradlew build` from the `hotwords/` subdirectory to verify everything compiles.

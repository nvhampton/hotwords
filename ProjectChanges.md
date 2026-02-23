# Hotwords Project Changes

## Session: 2026-02-21

### Round-Based Leaderboard Rework
- Replaced per-player score tracking with per-round entries shared by all players
- Backend: new `RoundEntry`, `RoundSubmission`, `RoundSubmissionResponse`, `GroupedScore` data classes
- Added SHA-256 session hashing (first 3 bytes → 6-char hex fingerprint) to identify devices
- Percentile calculation for round scores
- Grouped leaderboard: top 10 individual rounds, rest grouped by score, 24h window
- Frontend: `submitRound()` replaces `submitScore()`, new `renderLeaderboard()` with percentile banner, time-ago, session hash
- Later iteration: removed player names from leaderboard, now shows "N players" + device hash

### Local Mode Simplification
- Removed player name entry from lobby — replaced with +/- player count selector
- Removed all name references from gameplay (no more "Player X:" prefix)
- Players are now just `Player 1`, `Player 2`, etc. internally

### Orientation Reveal System
- **Initial state**: flat=reveal, upright=hidden (backwards from user expectation)
- **Fix**: Inverted all logic so flat=hidden, upright=revealed
- **Iteration 1**: uprightThreshold=45° — too easy to trigger
- **Iteration 2**: uprightThreshold=60° — made it worse (user wanted harder, meaning more upright)
- **Iteration 3**: uprightThreshold=35° — went the wrong direction entirely
- **Iteration 4**: uprightThreshold=85° — correct, phone must be nearly vertical
- **Unreveal sensitivity**: flatThreshold started at 35° (had to tilt almost flat to re-hide). User wanted it to re-hide quickly → raised to 80°
- **Final thresholds**: flat=80°, upright=85°, fade zone=80-85°
- **Key learning**: "harder to reveal" means higher angle (closer to 90°), not lower. The angle is beta from the DeviceOrientation API where 0°=flat, 90°=vertical.

### Fade Transition
- Added opacity transition zone between flat and upright thresholds
- Redact label fades out, word text peeks at 50% opacity in the transition range
- Styles reset cleanly when entering fully hidden or fully revealed states

### Hidden State Display (#### Pattern)
- **Bug: duplicate display** — renderWordProgress was writing #### to wordProgressEl while redactLabel also showed ####. Fixed by having local mode renderWordProgress only clear wordProgressEl.
- **Bug: inline styles overriding CSS** — getHiddenLabel() had `color: #555; font-size: 1.8rem` inline which always beat the CSS `:first-child` rule trying to set white/4rem. Inline styles always win over CSS selectors. Fixed by putting correct styles inline directly.
- **Bug: nested .word-text spans** — `wordDisplay` IS the `#currentWord` span (`.word-text`), not the container. Setting `wordDisplay.innerHTML = '<span class="word-text">...'` created broken nesting. Fixed to use `wordDisplay.textContent = currentWord`.
- **Multi-line layout**: redact-label is position:absolute, so it doesn't affect container height. Fixed by making it fill the box with `top/left/right/bottom: 0` + flexbox centering. Added `overflow-wrap: break-word` to container. Pattern uses `word-break: keep-all` so #### groups wrap at spaces.
- **Final pattern style**: 3rem, white (#fff), bold, letter-spacing 4px, line-height 1.4

### Context-Aware Reveal Hints
- `getHiddenLabel()` detects if accelerometer is active
- Mobile with accelerometer: "Hold vertical to reveal"
- Desktop/no accelerometer: "Press & hold to reveal or hold SPACE"
- Cleared static HTML default in redact-label element

### Partial Word Reveal Config
- Added `partialWordReveal` boolean flag (default: true)
- `true` (easy mode): individual words reveal as detected by speech, #### updates to show revealed words in teal
- `false` (hard mode): no partial reveals, must match full phrase, shows single block of #'s without word boundaries
- Guards both local and online mode word-by-word detection
- No UI toggle yet — just the internal flag

### Button Cooldowns
- **Initial**: single 3-second shared cooldown for both buttons
- **Final**: separate cooldowns — Got It: 1 second, Skip: 5 seconds
- Independent `gotItCooldown`/`skipCooldown` flags and `startGotItCooldown()`/`startSkipCooldown()` functions
- Applied to both local and online mode handlers
- Keyboard shortcuts (Enter/Tab) also respect cooldowns

### Microphone Permission
- Added `ensureMicPermission()` — calls `navigator.mediaDevices.getUserMedia({ audio: true })` to explicitly trigger the browser permission prompt
- Stream is immediately stopped after permission is granted (just needed the prompt)
- Called before `initSpeechRecognition()` in both `startLocalGame()` and the listen button click handler
- Fixes issue where revisiting the site wouldn't properly request mic access

### Speech Recognition Performance
- `interimResults: true` already provides streaming detection (near real-time)
- **Fix**: transcript processing was accumulating the entire session. Now limited to last 3 speech results (`Math.max(event.resultIndex, event.results.length - 3)`) to keep sliding-window Levenshtein bounded
- Levenshtein is O(m*n) but words are short (3-10 chars) so not a bottleneck

### UI Polish
- Celebration delay: 800ms → 1200ms
- Play Again button: added to local mode (was online-only)
- Score display: moved from large centered element to small fixed badge in top-right corner (1.6rem number, 0.6rem label)
- Got It / Skip buttons: vertical padding increased to 28px for larger touch targets
- Back button: fixed top-left, score: fixed top-right

### Deployment
- Switched from Docker to JAR-based deployment (16MB vs 112MB Docker image)
- t3.micro kept OOM'ing → upgraded to t3.small
- `build-and-deploy.sh`: builds fat JAR locally with `./gradlew shadowJar`, SCPs to EC2, runs deploy.sh remotely
- `deploy.sh`: installs Java 21 + Caddy if needed, systemd service, auto-starts
- Caddyfile: `hotwords.xyz { reverse_proxy localhost:8080 }`
- EC2 IP: 184.32.87.58, domain: hotwords.xyz (Cloudflare DNS)

### Timer Pause During Device Passing
- Timer pauses on correct guess (`timerPaused = true`), resumes when next player reveals phrase (tilt or tap)
- Timer display dims to 40% opacity when paused
- **Got It and Skip buttons blocked while paused** — `timerPaused` added as guard to both `localModeClaimVictory()` and `localModeSkipWord()`
- **Speech recognition paused while timer paused** — `recognition.stop()` on pause, `recognition.start()` on resume. Prevents processing audio during device handoff (less history accumulation, no accidental auto-match)
- Speech auto-victory path (`updateRevealedWords` → `localModeClaimVictory`) also blocked by the `timerPaused` guard

### Celebration Score Display
- Score + fire emojis shown in celebration overlay: `score` followed by `🔥` repeated `Math.min(score, 15)` times
- Fires only in celebration overlay (`showCelebrationLocal`), NOT in the persistent score badge (`updateScoreDisplay` is just `textContent = score`)
- Celebration overlay is `position: fixed` with its own z-index, fully independent from game layout
- **Known issue under investigation**: user reports the phrase box grows with fire count — could not reproduce in code review, all layout paths look clean. May be a visual artifact of celebration overlay on small screens.

### Lobby Redesign
- Local mode: selecting mode expands panel with Easy/Hard toggle + player count + dedicated Start button
- Online mode: double-click mode button to start (button text changes to "▶ Start")
- Easy/Hard toggle controls `partialWordReveal` flag (Easy = words reveal as spoken, Hard = full phrase only)
- Difficulty description text updates dynamically below toggle

### UI Iteration
- Got It / Skip buttons: padding increased to 50px vertical, border-radius reduced from 50px to 16px (rectangular, larger touch targets)
- Listen button: moved to fixed position top-left, next to back button
- Score badge: fixed top-right with back button and listen button on left
- Celebration fires: `score` + 🔥×score (capped at 15) at 2.5rem in celebration overlay
- Code cleanup: renamed `winnerName`/`winnerNameEl` → `celebrationInfo`/`celebrationInfoEl`, removed dead CSS rules

### Key Learnings
1. **Inline styles always override CSS rules** — no matter how specific the selector. If building HTML in JS, either use inline styles consistently or use classes without inline overrides.
2. **Know your DOM structure** — `wordDisplay` was `#currentWord` (a span), not the container div. Setting innerHTML with nested spans of the same class broke everything.
3. **DeviceOrientation beta**: 0°=flat on table, 90°=fully upright. "Harder to reveal" = higher threshold.
4. **Hysteresis matters** — with flat=80° and upright=85°, there's only a 5° hysteresis band. This makes the reveal feel snappy and deliberate.
5. **Docker on small instances is impractical** — a 16MB JAR + systemd is far more reliable than Docker on a t3.micro/small.
6. **Speech API `interimResults`** — gives streaming word detection for free, but transcript accumulates unboundedly over a session. Window the results.
7. **Use game state flags as guards everywhere** — `timerPaused` was already tracking device-passing state. Reusing it to block buttons, speech recognition, and keyboard shortcuts prevents entire categories of edge-case bugs with one flag.
8. **iOS async permission chaining** — DeviceOrientationEvent.requestPermission() must be called in a user gesture context. Mic permission (getUserMedia) can follow. Awaiting both sequentially in `startLocalGame()` prevents the first phrase from appearing before controls are ready.

## Session: 2026-02-22

### Security Hardening

Comprehensive security pass before sharing with colleagues. All changes in 4 files: `Application.kt`, `index.html`, `deploy.sh`, `Caddyfile`.

#### XSS Fixes (Frontend)
- Player names in online player list were injected raw via `innerHTML` — wrapped with `escapeHtml()`
- Leaderboard rendering: escaped `sessionHash`, coerced numeric fields (`score`, `playerCount`, `roundCount`, `totalRounds`) through `Number()` to prevent injection via server response
- Online mode word display and `getHiddenLabel()`: revealed words wrapped with `escapeHtml()` (defense-in-depth, phrases are server-controlled)
- `escapeHtml()` already existed in the codebase (creates a text node via `div.textContent`, reads back `div.innerHTML`) — just wasn't being used everywhere

#### Server-Side Input Validation (Backend)
- **Player names**: trimmed, capped at 20 chars, HTML tags stripped via regex (`<[^>]*>`) in SET_NAME handler
- **Room IDs**: validated against `^[a-zA-Z0-9_-]+$`, max 30 chars. Invalid IDs get WebSocket close with VIOLATED_POLICY
- **Score submission** (`POST /api/scores`): score clamped to 0..999, players list capped at 20 entries of 20 chars each, mode validated against whitelist ("local", "online"), roomId capped at 30 chars

#### Role Enforcement (Backend)
- `WORD_MATCH`: only non-hot players (guessers) — hot player index check
- `DESCRIBER_SLIP` / `DESCRIBER_FAIL`: only hot player (describer self-reports)
- `SKIP_WORD`: only hot player
- `CLAIM_VICTORY`: any room member (must have valid `sessionToPlayerId` entry)
- All actions require sender lookup via `sessionToPlayerId[this]` — unauthenticated sessions silently ignored

#### Resource Limits & Room Cleanup (Backend)
- **Room cap**: max 100 concurrent rooms. New connections beyond this get WebSocket close with TRY_AGAIN_LATER
- **Player cap**: max 20 players per room
- **Room cleanup**: TTL sweep tracks `roomEmptySince` timestamp. Rooms with 0 players and 0 sessions for >60s cleaned from all 4 maps (`rooms`, `roomStates`, `roomWords`, `roomScores`)
- **WebSocket config**: `maxFrameSize = 65536`, `pingPeriod = 30s`

#### Rate Limiting (Backend)
- `POST /api/scores`: 10 requests/minute/IP using `ConcurrentHashMap<String, MutableList<Long>>` with sliding window. Stale entries cleaned in TTL sweep
- WebSocket messages: 30 messages/second/connection. Excess messages silently dropped. Per-session timestamp list cleaned on disconnect
- **Kotlin gotcha**: `synchronized {}` is an inline lambda — can't use `continue` or `call.respond()` (suspension point) inside it. Fix: return a boolean flag from `synchronized` and act on it outside

#### Player ID Entropy (Frontend)
- Replaced `Math.random().toString(36).substring(2, 11)` (~47 bits) with `crypto.getRandomValues(new Uint8Array(16))` (128 bits) for player IDs stored in localStorage

#### Deploy Hardening
- **Caddyfile**: Added `X-Content-Type-Options nosniff`, `X-Frame-Options DENY`, `Referrer-Policy strict-origin-when-cross-origin`, `Permissions-Policy "camera=(), microphone=(self), geolocation=()"`. No CSP (inline scripts/styles in single-file app make it impractical)
- **systemd**: Dedicated `hotwords` system user (no home dir, nologin shell), `NoNewPrivileges=true`, `ProtectSystem=strict`, `ProtectHome=true`, `PrivateTmp=true`, `ReadWritePaths=/opt/hotwords`

#### What We Didn't Do (and Why)
- **Server-generated player IDs**: Would break reconnect flow, low risk among colleagues
- **CSRF on `/api/scores`**: Low-value target (game leaderboard)
- **Caddy rate limiting**: Requires custom-built Caddy binary. Ktor-level is simpler and sufficient
- **CSP headers**: Single-file app with inline scripts/styles makes CSP impractical without major refactoring

#### Word List Refresh
- Game word list updated with new categories (Famous Phrases & Sayings) and refreshed entries
- Removed some duplicates, improved phrase quality

### Key Learnings
9. **`synchronized {}` in Kotlin is an inline lambda** — you cannot use `continue` (break/continue in inline lambdas is experimental) or suspension points (`call.respond()`) inside it. Pattern: return a flag from `synchronized` and handle flow control outside.
10. **Defense-in-depth for innerHTML** — even when data is server-controlled (like game phrases), wrap with `escapeHtml()`. Today's trusted data source could become tomorrow's user input.
11. **Room cleanup needs hysteresis** — don't remove rooms the instant they're empty (players might be reconnecting). Track an `emptySince` timestamp and require sustained emptiness (60s) before cleanup.

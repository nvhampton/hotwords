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
- Caddyfile: reverse proxy to localhost:8080 with auto HTTPS
- Hosted on EC2, domain managed via Cloudflare DNS

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

## Session: 2026-02-22 (cont.)

### Online Mode Game Summary & Leaderboard

Online mode previously showed a bare "Game Over!" text with a Play Again button — no score summary, no phrase history, no leaderboard access. Local mode already had a polished end-of-round overlay. This session brought feature parity.

#### Phrase History Tracking (Online)
- `NEW_WORD` handler now sets `phraseStartTime = Date.now()` in online mode
- `ROUND_WON` handler pushes `{ phrase, status: 'got-it', time }` to `phraseHistory`
- `WORD_SKIPPED` handler pushes `{ phrase, status: 'skipped', time }`
- `DESCRIBER_FAILED` handler pushes `{ phrase, status: 'skipped', time }`
- `endGame()` online branch pushes current phrase as `timed-out` (same pattern as local mode)
- Reuses existing `phraseHistory` array and `phraseStartTime` variable — no new state needed

#### Game Summary Overlay (Online)
- Replaced bare "Game Over!" text + `playAgainBtn` with `showGameSummary(localScore, phraseHistory)` — same overlay local mode uses
- Shows score with fire emojis, scrollable phrase list with ✓/✗ prefixes and times, Play Again + Leaderboard + Back to Lobby buttons
- Play Again in the overlay delegates to `playAgainBtn.click()` which already handles online mode (`NEW_ROUND` message)
- `NEW_ROUND` handler now calls `dismissGameSummary()` + resets `phraseHistory = []` so overlays clear when any player starts a new round

#### Score Submission with Phrase Data
- Online score submission now sends actual phrase history instead of empty array
- Maps `phraseHistory` entries to `{ phrase, status, timeSeconds }` format matching the API

### Timer Pause on Focus Loss (Local Mode)
- Added `visibilitychange` event listener that pauses/unpauses the timer when the tab or app loses/regains focus
- Only active in local mode during an active game (`!isLocalMode || !gameActive` guard)
- Reuses existing `timerPaused` flag and `.paused` CSS class (40% opacity dim) — same mechanism used for pass-between-players pause
- When tab is hidden: `timerPaused = true`, timer dims. When visible again: `timerPaused = false`, timer resumes

### Key Learnings
12. **Reuse existing UI components across modes** — the game summary overlay, `timerPaused` flag, and `phraseHistory` array were all built for local mode but designed generically enough to work for online mode with minimal wiring.
13. **Track events at the source** — adding phrase history entries in message handlers (ROUND_WON, WORD_SKIPPED, DESCRIBER_FAILED) rather than trying to reconstruct history after the fact is simpler and more reliable.
14. **`visibilitychange` vs `blur`/`focus`** — `visibilitychange` is the correct API for detecting tab switches and app minimization. `blur`/`focus` fire for in-page focus changes (clicking between elements) which would cause false pauses.

## Session: 2026-03-16

### Host System for Online Rooms (v0.11.33–0.11.49)
- Added `hostPlayerId` to `RoomState` — first player in a room becomes host
- Auto-promotes longest-tenured player when host disconnects (TTL expiry or leave)
- `SET_PHRASES` message: host uploads custom phrase list to server, stored in `roomCustomPhrases`
- `getWordForRoom()` priority: custom phrases > theme > defaults
- Theme setting (`SET_NAME`, `NEW_ROUND`) gated to host only
- Crown badge (👑) shown next to host in player list and ready overlay
- `PLAYER_LIST` broadcasts include `hostPlayerId` (all 9+ broadcast sites updated)

### Custom Category System (v0.11.41–0.11.53)
- 💬 overlay changed from comma-separated phrase input to single category text input
- Server `/api/generate-phrases` accepts `category` field — generates 70 phrases for that category via Claude Haiku
- Categories of the day: `GET/POST /api/categories` — stores category name + cached phrases for 24h
- Played categories appear as purple-bordered topic pills on lobby, served with cached phrases (no repeat API calls)
- Category label shown on game screen during play
- On game end, custom category + phrases submitted to server cache

### Desktop Keyboard Improvements (v0.11.33–0.11.38)
- Enter and Tab work as Got It / Skip in online mode (not just local)
- Key hints `[Enter]` and `[Tab]` shown on buttons for desktop users
- Spacebar works on the "Ready for you" onboard screen to start the game
- Skip button added to onboard ready screen (bottom-right)
- Desktop-specific text: "Press Spacebar to reveal" instead of "Hold phone upright"
- Desktop hint on hidden word: "Hold Spacebar to reveal"

### Timer & Gameplay Improvements (v0.11.40–0.11.54)
- **+4s bonus**: Getting a phrase right adds 4 seconds when timer is under 6s remaining
- **Visual "+4s" animation**: Green text floats up from timer on bonus
- **Final word shown**: When time expires, the active phrase stays visible instead of clearing
- **Seamless word swap**: Next word loads during celebration animation, no visible delay after

### Bug Fixes
- **`gameStartTime` not reset on NEW_ROUND** (v0.11.33): Caused stale timer sync and broken ready states between rounds with >2 players
- **Cooldowns stuck between rounds** (v0.11.39): `gotItCooldown`/`skipCooldown` never reset when game ended — timeout callback's `if (gameActive)` guard prevented re-enable. Fixed by explicit reset in GAME_STARTED and NEW_ROUND handlers
- **`hasClaimedVictory` not reset after pass** (v0.11.38): Got It button stopped working after first use in local mode. Fixed by resetting on reveal
- **Skip cooldown starting before reveal** (v0.11.38): `startSkipCooldown()` restarted a fresh 5s timer on reveal instead of just re-enabling. Fixed with direct reset
- **`customizeInput` null crash** (v0.11.48): Replacing the textarea with a category input broke the old JS references, killing all page JS on load
- **`sendPhrasesToServer` missing** (v0.11.49): Function and connect-time SET_PHRASES send were lost during revert/re-apply

### Key Learnings
15. **Cooldown state machines need explicit resets** — Relying on timeout callbacks to reset state fails when the game ends before the timeout fires. Always reset cooldowns at state transitions (round start, round end).
16. **Revert carefully, re-apply surgically** — When reverting a file to fix lobby breakage, all incremental changes must be re-applied individually. Using an agent for bulk re-application works but risks missing items (like `sendPhrasesToServer`).
17. **Null element references kill everything** — In a single-file app, one `null.value` crash in startup code prevents ALL subsequent JS from running. Always guard element references when refactoring HTML.
18. **Cache AI results server-side** — Generating phrases via API on every pill click is wasteful and slow. Caching phrases alongside category names means instant loading for popular categories.

## Session: 2026-03-19

### AI Phrase Generation Cleanup (v0.11.76–0.11.77)
- Added prompt rules: no acronyms/abbreviations, no hyphens/apostrophes/punctuation, correct spelling only
- Server-side sanitization as safety net: `replace(Regex("[^a-zA-Z0-9 ]"), " ")` strips any remaining special chars, normalizes whitespace, lowercases

### Server-Side Round Expiry (v0.11.78)
- **Bug**: No server-side mechanism to end rounds — if all players left mid-game, `gameStartTime` stayed set, new joiners stuck as spectators forever
- **Fix**: TTL cleanup loop (every 5s) now checks: if `gameStartTime` is set AND (round duration + 10s grace elapsed OR all active players gone), auto-reset room to lobby, fold pending players into active roster, broadcast NEW_ROUND + PLAYER_LIST
- Also: new players joining an already-expired round go straight to active roster instead of spectator mode

### Category Delete via Long-Press (v0.11.79)
- 6-second long-press on custom (purple) category pill triggers confirm dialog → DELETE /api/categories/{name}
- `longPressTriggered` flag prevents the normal click/select from firing after a delete

### Online Lobby Overhaul (v0.11.80–0.11.82)
- **Before**: Entering online mode showed bare game screen with "Waiting for players..."
- **After**: Immediately shows ready overlay as lobby with player list, mic settings, share invite
- Ready button disabled with "Waiting for players..." until 2+ players join
- Share invite (copy link) embedded directly in ready overlay when <2 players
- Share invite changed from OS share dialog to direct clipboard copy
- **Bug fix**: Ready overlay not dismissing on Escape/back — WebSocket PLAYER_LIST messages were re-showing it after `showLobby()` hid it. Fixed by disconnecting WS before hiding overlays and guarding PLAYER_LIST handler with `gameScreen.classList.contains('active')`
- Z-index fix: ready overlay → 1075 (was 1050, conflicted with game summary)

### Per-Player Mic Status (v0.11.83)
- New `SET_MIC` message: client sends on connect and on mic toggle change
- `micEnabled` field added to Player and PlayerInfo data classes
- Ready overlay shows 🎙️ (on, full opacity) or 🔇 (off, dimmed) per player
- Minimal traffic — only fires on explicit user action (connect + toggle)

### Bug Fixes
- **Stale game-in-progress** (v0.11.78): Rooms stuck in "game in progress" forever when all players left mid-game
- **Ready overlay ghost** (v0.11.82): Overlay persisted after Escape/back due to WS messages re-showing it
- **Z-index stacking** (v0.11.83): Ready overlay and game summary both at 1050, undefined layering
- **gameStartTime double-read** (v0.11.83): TTL cleanup read volatile state twice without local capture; fixed with `val gameStart = state.gameStartTime`

### Key Learnings
19. **Server-side timers are essential for stateful rooms** — Client-side-only timers mean the server can't clean up stale game state. The TTL cleanup loop was the right place to add round expiry since it already runs every 5s.
20. **WebSocket close is async** — Calling `ws.close()` doesn't immediately stop message handlers. If `showLobby()` hides an overlay before disconnecting, incoming messages can re-show it. Disconnect first, then clean up UI.
21. **Fixed-position overlays need unique z-indexes** — Multiple `position: fixed; z-index: 1050` overlays create undefined stacking. Assign distinct values in a clear hierarchy.
22. **Prompt engineering + server sanitization = defense in depth** — AI models mostly follow prompt rules but can slip. A regex strip on the server ensures no punctuation reaches clients regardless.

## Session: 2026-04-06

### Android Voice Recognition Improvements (v0.13.19)
Three coordinated changes to narrow the gap between desktop Chrome and Android Chrome speech recognition. Android STT is genuinely worse (lower-accuracy on-device model, dropped words after restarts, ~5–10s silence auto-end), so the fix is to be more forgiving on Android and waste fewer hypotheses.

- **`maxAlternatives = 5`**: Web Speech API only returns 1 hypothesis by default. We now request 5 and run match logic against all of them, taking the best similarity. Especially valuable on Android where the top hypothesis is often noisier than the runner-up. The describer slip check still uses only the top hypothesis to avoid false positives from low-confidence alternatives.
- **Android-only fuzzy threshold drop**: `IS_ANDROID` UA detection lowers `FUZZY_THRESHOLD` and `WORD_FUZZY_THRESHOLD` from 0.85 → 0.75 on Android only. Desktop remains strict.
- **Pre-warm in online mode**: On Ready click, recognition is started immediately with new `isPrewarming` flag so `onresult` ignores results during the lobby wait. The first ~300ms of dropped audio (Android's Web Speech API quirk) happens during the ready wait instead of mid-round. `GAME_STARTED` clears `isPrewarming` and recognition begins processing. `hideReadyOverlay()` cancels the warm-up if the player backs out before the game starts. (Local mode already had effective pre-warming via `launchLocalGame`.)

### Key Learnings
23. **Web Speech API alternatives are free accuracy** — `maxAlternatives` defaults to 1 but is essentially free to bump to 5. The runner-up hypotheses are often what the speaker actually said, especially on lower-quality STT backends like Android Chrome's. Run match logic against all of them, not just the top.
24. **STT quality is per-platform, not per-browser** — Desktop Chrome and Android Chrome are both "Chrome" but use entirely different speech models. Treat them as separate STT engines with separate tuning constants.
25. **Pre-warming matters because the first ~300ms of recognition is unreliable** — Web Speech on Android frequently drops the first words after `start()`. Starting recognition during a UI wait state (lobby, ready overlay) and gating result processing with a flag eats the cost without affecting gameplay.

## Session: 2026-06-16

### IPv6-only origin + EC2 Instance Connect Endpoint (deploy)
Migrate the EC2 instance off its auto-assigned public IPv4 to eliminate the ~$3.65/mo IPv4 hourly charge. Cloudflare's proxied edge already does dual-stack at the front; the origin only needs IPv6.

- **VPC/subnet**: Amazon-provided IPv6 `2600:1f13:24a:2e00::/56` on VPC; `/64` on subnet; `::/0 → igw` on the route table; `AssignIpv6AddressOnCreation=true` for future launches.
- **Instance ENI**: assigned `2600:1f13:24a:2e00:bea1:ce28:6e4a:a17c`. Security group gained `tcp/80` and `tcp/443` from `::/0` (existing v4 rules kept while Cloudflare still has the A record).
- **SSH path replacement**: residential ISPs are typically v4-only, so dropping the public IPv4 would kill direct SSH. Solution: EC2 Instance Connect Endpoint (`eice-0babd0133d83c9794`) in the same subnet with its own SG (`sg-011c8c012893d5f55`), and a new ingress rule on the instance SG allowing tcp/22 from the EICE SG. SSH/scp go through `aws ec2-instance-connect open-tunnel --instance-id %h` as a `ProxyCommand`, keeping the existing keypair.
- **Deploy script** (`deploy/build-and-deploy.sh`): default `HOST` switched from `184.32.87.58` to the instance ID. When `HOST` matches `i-*` the script appends the EICE ProxyCommand. `SSH_OPTS` refactored from string to bash array so options containing spaces (the ProxyCommand value) survive expansion.
- **Caddy unchanged**: it was already binding `*:80`/`*:443` (dual-stack), so it serves on the IPv6 with no Caddyfile change. Verified end-to-end with `curl --resolve` from inside the instance against `[ipv6]:443` → 200.

### Cutover
- Cloudflare: added AAAA `hotwords.xyz` → `2600:1f13:24a:2e00:bea1:ce28:6e4a:a17c` (proxied), kept A in place initially.
- Tried `modify-subnet-attribute --no-map-public-ip-on-launch` + stop/start; the stop/start gave the ENI a *new* auto-assigned public IPv4 (`34.222.177.254`) anyway — the subnet flag only affects new launches, not existing ENIs.
- Fix: `aws ec2 modify-network-interface-attribute --no-associate-public-ip-address` on the existing ENI. The public IPv4 was released immediately with no stop/start required and no impact to the IPv6 / SSH-via-EICE / running service. Verified `PublicIpAddress: null` and Cloudflare → origin still 200.
- Cloudflare A record now stale (points at `184.32.87.58`, no longer ours) — leaving deletion to a follow-up since proxied A doesn't matter when the origin only listens on v6.

### Post-cutover bug: clue generation broken
After dropping the public IPv4, `POST /api/generate-phrases` started timing out (`io.ktor.client.network.sockets.ConnectTimeoutException` against `api.anthropic.com`). `api.anthropic.com` is dual-stack, so the IPv6 path works — but the JVM defaults to `preferIPv6Addresses=false`, so `getAllByName` returned IPv4 first and Ktor's CIO engine tried it and timed out without falling back to v6. Fix: add `-Djava.net.preferIPv6Addresses=true` to `ExecStart` in the systemd unit defined by `deploy/deploy.sh`. Verified the live endpoint returns phrases in ~2s after redeploy.

### Key Learnings
26. **AWS bills for every attached public IPv4, EIP or not** — Since Feb 2024, the $0.005/hr charge applies to auto-assigned public IPv4s too. "Elastic IPs: none" is not the same as "no public IPv4."
27. **`modify-network-interface-attribute --no-associate-public-ip-address` works on running ENIs** — Older AWS docs imply you need to swap the ENI or relaunch the instance to drop an auto-assigned public IPv4. Not anymore: the API call releases it in-place, no downtime. Try this first before any ENI surgery.
28. **Subnet `MapPublicIpOnLaunch` is launch-time only** — Setting it to false does NOT affect existing ENIs across stop/start. They keep their per-ENI "associate public IP" attribute baked in at launch, and a fresh public IP is assigned on every start until you flip the ENI attribute.
29. **Bash arrays beat option strings for ssh/scp wrappers** — `SSH_OPTS="-o Foo=$X"` used unquoted (`scp $SSH_OPTS …`) breaks the moment `$X` contains a space, because re-expansion does word-splitting but not quote-removal. Use `SSH_OPTS+=(-o "Foo=$X")` and `scp "${SSH_OPTS[@]}" …`.
30. **EC2 Instance Connect Endpoint takes ~5 minutes to provision** — Creation returns immediately but `State` stays `create-in-progress` for around 5 min before becoming `create-complete`. Plan automation around this; don't assume it's ready right after the API call.
31. **IPv6-only origins must tell the JVM to prefer IPv6** — `java.net.preferIPv6Addresses` defaults to `false`. On a dual-stack JVM with no IPv4 egress, outbound HTTPS clients (Ktor CIO, OkHttp, etc.) resolve A+AAAA, try the A first, and hang on connect timeout instead of falling back to the AAAA. Set `-Djava.net.preferIPv6Addresses=true` in `ExecStart` for any JVM running on an IPv6-only host.

## Session: 2026-07-31

### In-round UI redesign: Got It / Skip swap + layout cleanup
User asked for Got It moved to the right / Skip to the left, plus a broader cleanup of the in-round screen ("pretty meh"). Went transformative on layout/spacing while keeping every element ID, JS hook, and message-handler class untouched, since the ~5500-line frontend has no tests and a lot of state (local vs online, easy vs hard, mobile vs desktop) hangs off exact IDs and class names.

- **Button order**: swapped Skip/Got It in the DOM (Skip first). Got It stays visually heavier (filled mint, bigger shadow); Skip stays the outline/secondary treatment.
- **Body anchoring**: game screen was full-page `justify-content: center`'d like the lobby, which on any reasonably tall viewport left huge dead space above the header and below the toolbar. Added a `body.in-round` class (toggled in `showGameScreen()`/`showLobby()`) that switches to `justify-content: flex-start` with a fixed top offset, so the round content sits near the top instead of floating in the middle of a mostly-empty page.
- **Timer badge**: the countdown used to live inline below the phrase card and grow via `style.fontSize` (up to 8rem in the final 8 seconds) directly in normal flow — each tick reflowed the buttons below it. Moved `.timer-display` to `position: absolute`, overlapping the top edge of a new `.stage` wrapper around the phrase card, so the same dramatic font-size growth now happens without touching layout of anything below it.
- **Score chip**: was bare floating text (`fixed` top-right); gave it a bordered card background matching the existing `.back-btn` treatment so the two fixed corner elements read as a pair.
- **Toolbar**: Show Clue / mic / keyboard-toggle buttons had three different heights (10px/12px vertical padding vs a fixed 44px circle), which is part of what made the row look scrappy. Standardized all three to 44px height.
- **Real bug found**: the global `button { margin: 10px; }` rule was never overridden for `.gotit-btn`/`.skip-btn` (unlike the toolbar buttons, which already had `margin: 0`), so those two buttons got both the flex `gap` *and* a 10px own-margin — double-spaced and asymmetric against the row's outer edge. Added `margin: 0` for the button-row buttons.
- **Stray placeholder**: the transcript box defaulted to showing a lone `-` character before any speech was detected. Changed the three JS call sites (initial HTML + two reset points) to use `''` instead, relying on the box's existing `min-height` to hold its layout space.
- **Found and fixed a reorder-defeating bug**: `showLobby()` had a defensive line — `buttonRow.insertBefore(skipBtn, playAgainBtn)` — left over from some earlier state management. Since `playAgainBtn` is always the last child, this silently reset the button order back to Got-It-first every time a player returned to the lobby (i.e., after every single round). Fixed to explicitly pin the full order (`skip → gotIt → playAgain`) instead of relying on relative position to `playAgainBtn` alone.

### Key Learnings
32. **A "defensive" DOM reset can silently undo a reorder.** `showLobby()`'s `insertBefore(skipBtn, playAgainBtn)` looked like harmless cleanup but actually re-asserted the *old* button order on every lobby return, because it only anchored relative to the last element, not the full intended sequence. When reordering DOM nodes that already have a stray "just in case" reset elsewhere, grep for every `insertBefore`/`appendChild` touching those nodes — not just the obvious render path.
33. **`font-size` transitions in normal flow cause layout jank; `position: absolute` badges don't.** The countdown timer's dramatic grow-in-the-final-seconds effect was already good UX, but growing a flow element's font-size pushes every sibling below it on each tick. Pulling the same element out of flow (absolute, anchored to a positioned ancestor) keeps the drama without the jank — same JS, zero JS changes needed for the fix.
34. **Global element-selector rules (`button { margin: 10px }`) are easy to under-override.** Three of five button classes in the game screen already had `margin: 0` to cancel this; the two newest (`.gotit-btn`, `.skip-btn`) didn't, and the bug was invisible in a screenshot review — it only shows up as "the spacing feels a little off" until you diff computed styles or the CSS rule list itself.

### Home screen redesign: drop the emoji iconography
Same session, follow-on request — the lobby's emoji-driven UI ("irrelevant and silly") got the same treatment. Bigger risk than it looked: several emoji glyphs were load-bearing UI elements with hidden behavior, not decoration.

- **Title row**: `🔥 Hotwords 💬` collapsed to a plain "Hotwords" wordmark. The 🔥 (`presetFire`) and 💬 (`customizeBalloon`) spans weren't just icons — 🔥 click reset to built-in phrases and long-press (1.5s) toggled a hidden "RKO" easter egg mode; 💬 click opened the custom-category overlay. The 💬 balloon was already fully redundant with the "+" pill in the topic picker (both called the same `openCustomizeOverlay()`), so it was just deleted. The RKO long-press was moved onto the title wordmark itself, layered alongside its existing click-to-leaderboard handler (long-press wins over the trailing click via the same `somethingTriggered` guard pattern already used elsewhere in the file for long-press-vs-click disambiguation).
- **Discovered mid-flight**: the plan was originally to attach the RKO long-press to the "Classic" topic pill (`topics.json`'s `default` entry) instead of the title, reasoning that pill was functionally redundant with the 🔥 reset behavior. Turned out `topics.json` had *already* been intentionally emptied to `[]` in this repo's uncommitted working state (no built-in topic pills at all anymore) — so there was no "Classic" pill to attach anything to. Caught before it shipped as a completely unreachable dead feature.
- **Real bug found**: `selectTopic()` (fired by clicking any built-in topic pill) never cleared `customPhrases`/`customCategory`, only `presetFire`'s click handler did. Since `getRandomPhrase()` checks `customPhrases` before anything else, selecting a normal topic pill while a custom AI category was active wouldn't actually switch off the custom phrases. Fixed by clearing custom-category state unconditionally at the top of `selectTopic()` (safe — it's never called for custom-category pills, which manage their own state separately).
- **Contrast bug found**: `.custom-active-banner .custom-active-text` was `rgba(255,255,255,0.85)` (near-white) sitting on a ~15%-opacity tint over the light page background — a leftover from when this banner was designed against a dark theme. Switched to `var(--hw-ink)`.
- **"Add category" dialog was a different app**: `.customize-overlay`/`.customize-dialog` still used the pre-`--hw-*` dark theme (navy/purple gradient `#1e1e3a`→`#2a2a4a`, `rgba(0,0,0,0.85)` backdrop, white text) while every other overlay in the app had long since moved to the light card system (`var(--hw-card)`, `var(--hw-line)`, ink text). Rebuilt it to match `.game-summary`/`.pause-overlay`'s language. Also found the input was styled via `.name-input` (built for the dark online-mode expand section) — split off a dedicated `.customize-input` class instead of continuing to borrow cross-purpose styling.
- **Dead code removed while in there**: `parseCustomPhrases()`, `buildCustomPhraseList()`, `customPhraseSource`, `updateCustomizeCount()`, and the `customizeCount`/`customizeInput` DOM lookups were all leftovers from a pre-AI-generation "paste your own phrase list" flow — defined, never called, never read. Removed rather than left to rot further.
- **Toggle color**: Free Play/Timed active state changed from the coral/pink accent gradient to a neutral slate-grey gradient per explicit request (the accent pink is already heavily used for the primary CTA elsewhere; grey reads as a calmer, more "settings toggle" affordance here).
- **Kept**: the 🎲 on the "Random" pill — explicitly called out as a fine, normal game-UI convention rather than "silly" decoration, unlike the title-row icons.

### Key Learnings
35. **An emoji in a UI can be a hidden feature, not decoration.** Before deleting an emoji-labeled element, grep every listener attached to it. Here, two of three title-row emoji had click *and* long-press handlers wired to state changes (default-phrase reset, a secret mode toggle) that weren't visible from a screenshot. "Remove the silly icons" still requires relocating the behavior underneath, not just deleting the span.
36. **Re-verify assumptions against the actual working tree mid-task, not just at the start.** The plan to reuse the "Classic" pill as the new home for a long-press easter egg was reasonable when written, but the pill's existence depended on `topics.json` content that had already been intentionally changed earlier in this same uncommitted session. A plan step that reads "attach to X" is a claim that X currently exists — worth a quick grep/curl right before implementing, not just during initial exploration.
37. **A design-system migration that isn't finished leaves genuinely different apps stitched together.** This file had at least three untouched dark-theme fragments (online player panel, custom-active banner text, and the entire add-category dialog) from before the `--hw-*` light theme existed. Each one was invisible in isolation (nothing crashes, no lint catches "wrong color") and only stood out as "old UI" once looked at side-by-side with the redesigned screens around it.

### Category-completion celebration (local mode)
User asked that finishing all words in a category end the round with a congratulations message, later refined mid-task to also show elapsed time (e.g. "completed this category in 4m45s"). Scoped to Local Mode only (Free Play + Timed) after confirming with the user — Online Mode has no per-room "phrases already won" tracking server-side, and adding it would mean new `Application.kt` state plus a new WebSocket message, a materially bigger change than the user was asking for.

- **New tracking**: `correctPhrasesThisRound` (distinct phrases won via Got It) is separate from the pre-existing `usedPhrasesThisRound` (phrases *shown*, used only to avoid immediate repeats — includes skips, and wraps around once exhausted). Reused the three existing `usedPhrasesThisRound.clear()` call sites to also reset the new set, and added `getActivePhrasePool()` as a shared helper (previously the custom/topic/rko/classic pool selection logic was inlined only in `getRandomPhrase()`).
- **Completion check** lives in `localModeClaimVictory()`, right after the phrase is recorded as `'got-it'`: if `correctPhrasesThisRound.size >= getActivePhrasePool().length`, call `endGame(true, elapsedMs)` instead of `nextPhrase()`.
- **Avoided a double-record bug**: `endGame()` already had unconditional logic to push the "phrase that was active when the game ended" onto `phraseHistory` (for the timer-expiry/timed-out case, where the round ends mid-phrase). Since the category-complete path already pushed that same phrase as `'got-it'` before calling `endGame()`, this needed a `&& !categoryComplete` guard to avoid appending it a second time as `'skipped'`/`'timed-out'`.
- **Duration source**: added `localRoundStartTime`, set once in `launchLocalGame()` (covers both the initial start and every "Play Again" restart, since both paths call that function). Elapsed time is `Date.now() - localRoundStartTime` computed live at the moment of completion, not summed from per-phrase times, so it reflects real wall-clock time including any thinking pauses.
- **Verified live**, not just by reading the diff: ran the actual server, used the browser's JS console to inject a 3-phrase custom category (`customPhrases = [...]`) and call `localModeClaimVictory()` directly three times, confirming score/set state after each call and the final "Congratulations! You completed this category in Xs!" text in both the status line and the game-summary overlay title.
- **Testing gotcha**: an earlier `./gradlew run` was still bound to port 8080 when a later `pkill` pattern didn't match it, so a second `./gradlew run` attempt silently failed (`BindException`) in the background while curl health-checks kept passing against the *old* process — which, confusingly, still picked up the new `formatDuration` code after a plain `./gradlew build` (Ktor serves static resources from `build/resources/main` fresh per request, no restart needed) but not the very latest edit made after that build ran. Lesson below.

### Key Learnings
38. **`./gradlew run` serves static resources live from `build/resources/main`, no JVM restart required** — a plain `./gradlew build` (or even just letting `processResources` run) is enough to make an already-running dev server pick up frontend-only changes. Restarting is only needed for Kotlin/backend changes. But don't assume a passing `curl` health check means *your* server instance is the one answering — `lsof -ti:8080` before trusting it, especially after a `pkill` whose pattern might not have matched.
39. **When a round-ending path already recorded the in-flight phrase's outcome, guard the generic "phrase active when game ended" fallback in the shared end-of-round function** — otherwise a specific completion path (won on the last phrase) double-records that phrase under a second, contradictory status in the history list.

### In-round button prominence swap: Show Clue up, Skip down
User asked to make Show Clue more prominent and Skip less prominent, and liked the idea of a literal position swap: Show Clue moved from the small toolbar row into the primary `.button-row` (same size/weight as Got It), Skip moved from the primary row down into the toolbar (`.listen-row`) as a small pill next to the mic/reveal/keyboard-toggle icons.

- **CSS keys off container, not just class**: `.button-row .gotit-btn, .button-row .skip-btn { flex: 1; min-width: 130px; }` and `.listen-row .clue-btn, .listen-row .listen-btn, ... { height: 44px; ... }` were already scoped by *parent* selector, not just element class — so moving the DOM nodes between containers mostly "just worked" once the selectors were updated to reference the new occupant of each row (swapped `.skip-btn` for `.clue-btn` in the button-row rules, and vice versa for listen-row), plus one added override block per button to resize it for its new context (clue-btn scaled up to button-row size; skip-btn's base large-button styles overridden back down to pill size in listen-row).
- **Reused, not rewrote, the previous session's reorder-defeating-bug fix**: `showLobby()` has an explicit DOM-order reset (`buttonRow.insertBefore(...)`) added in an earlier session specifically because a stray reset was found silently undoing a button reorder on every lobby return ([[Key Learning 32]] from the prior session). Since skip-btn no longer lives in `.button-row` at all, that reset had to be split: one line re-pins clue-btn/gotIt-btn/play-again-btn order in `.button-row`, a new second line re-pins skip-btn as the first child of `.listen-row`. Skipping this update would have silently reintroduced the exact bug from last session, just for the new layout.
- **Verified live**: ran the dev server, played through Show Clue (hint text appeared) and Skip (new phrase loaded, button greyed into its 5s cooldown) in their new positions, then did a full Back-to-Lobby → Start cycle to confirm the `showLobby()` reset didn't move either button back to its old spot.

### Key Learnings
40. **A reorder-scoped CSS selector (`.container .element`) mostly self-corrects when you move the element to a new container** — but any *sibling-relative* reset logic elsewhere (an `insertBefore` chain, a `nextElementSibling` check, etc.) hard-codes the old container and needs updating in lockstep, or the next screen transition silently reverts the move.

## Session: 2026-08-04

### Reveal system overhaul, mic fix, fullscreen, clue polish (local mode playtesting round)
Extended real-device playtesting with the user's son surfaced a batch of local-mode UX issues, fixed iteratively:

- **Tilt-to-reveal made an explicit opt-in toggle (🤳)**, hidden on desktop (no `DeviceOrientationEvent` support) and remembered across games via `localStorage`. Tap-to-reveal on the phrase card was replaced with a dedicated hold-button (👁️) so the two reveal mechanisms don't fight over the same gesture.
- **Fixed early re-reveal bug**: claiming victory/skipping while still holding the phone upright (tilt) or holding the reveal button immediately re-revealed the *next* phrase before the player had a chance to look away. Root cause was the continuous `deviceorientation` event stream firing between the phrase transition and the next sensor sample; fixed with a `revealArmed` flag checked in the orientation handler and reset on `showNewWordLocalMode()`.
- **Mic tap-mode was structurally broken** — only long-press/continuous mode actually heard speech. Root cause: `timerPaused` was overloaded for two unrelated purposes (device-pass-off pause vs. give-the-player-a-beat-to-talk pause), and `recognition.onresult`'s `if (timerPaused) return;` guard silently discarded all speech during tap mode. Fixed by deleting the separate tap-mode code path entirely and making the toggle button drive the same continuous-listening behavior as long-press, per the user's explicit ask.
- **Mic preference remembered**: `hotwords_mic_pref` in `localStorage`, auto-resumes continuous listening on `launchLocalGame()` if it was on last round.
- **Transcript ticker**: live speech preview truncated to the last 40 characters (`TRANSCRIPT_DISPLAY_CHARS`) with `white-space: nowrap; overflow: hidden; text-overflow: ellipsis`, so it reads as a FIFO ticker instead of wrapping/growing.
- **Fullscreen on Start**, mobile-only (`isMobileDevice()` — touch-capable OR mobile UA regex, deliberately separate from `isOrientationCapableDevice()` since fullscreen shouldn't depend on orientation-sensor support). Mirrored the technique and meta-tag set from `/flashcards`.
- **Bigger in-round buttons** per user feedback that "buttons are pretty small."
- **Clue display restyled** (font/color/style) since clues are "a key part of the game mode" — indexed/labeled clue lines with a `.latest`-highlighted most-recent clue and `scrollIntoView`; max-height bumped so the box doesn't scroll too early.
- **Auto-first-clue**: when tilt-to-reveal is disabled, the first clue now shows automatically on every new phrase, giving manual-reveal players a head start equivalent to what tilt players get for free by holding the phone upright.
- **Typed-answer flow**: replaced the Check button with auto-accept-on-every-keystroke using a stricter threshold (`TYPED_FUZZY_THRESHOLD = 0.92`) than voice's looser fuzzy match, since typing has no STT-noise excuse for a loose match. Found and fixed a second, subtler instance of the same bug: the *per-word* partial-reveal matching (`updateRevealedWords`/`checkWordInTranscript`) still used the old loose threshold on the typed path even after the full-phrase check was fixed, so partial words kept accepting a couple characters early. Added an explicit `threshold` param to both functions instead of a single global constant.
- **Autofill mitigation**: added attributes to stop Android from offering to fill the type-answer box with saved passwords/addresses/payment info, while explicitly leaving `autocomplete` itself untouched per the user's request ("i dont mind autocomplete being on the box").

### AI phrase-generation quality: model upgrade + self-filter pass
User's son had been playing enough that "the quality of the phrases is starting to become the limiting factor." Discussed three options (model upgrade, two-pass generate-then-filter, per-phrase player rating); user approved the first two now, explicitly deferring rating as a future enhancement (noting time-per-phrase / never-guessed-phrases-at-round-summary already gives a related signal).

- **Model swap**: `/api/generate-phrases` moved from `claude-haiku-4-5-20251001` to `claude-sonnet-5` for both the category-mode and example-mode prompts.
- **Two-pass self-filter**: after the existing generation call produces `candidatePhrases`, a second Sonnet call is sent the full candidate list and asked to remove phrases that are too generic/descriptive, obscure, near-duplicate, awkward/confusing, or not family-friendly, returning only the phrases to keep. The filter response is intersected against the original candidate set (`kept.filter { candidatePhrases.contains(it) }`) to guard against the second pass hallucinating new phrases instead of just paring the list down.
- **Fallback-safe by design**: the filter pass is skipped entirely (falling back to unfiltered candidates) if candidates are empty, if `checkDailyClaudeCap()` reports the daily cap already hit (the filter call counts as a second API call against that same cap), if the filter HTTP call errors or fails to parse, or if the filtered result comes back smaller than `min(safeCount, candidatePhrases.size)` (guards against the model being overzealous and gutting the list below what's needed). Every failure mode degrades to "one generation pass, no filtering" rather than an empty phrase list or a 500.
- **`/api/generate-clues` intentionally left on Haiku** — not part of the user's approved scope; noted as a possible future follow-up, not changed.

### Key Learnings
41. **A flag reused for two unrelated pause semantics is a landmine, not a shortcut.** `timerPaused` meant "device is being physically passed between players" in one code path and "give the current player a beat before restarting recognition" in another. The second meaning's guard (`if (timerPaused) return`) silently ate all speech recognition results during mic tap-mode, and the fix that actually worked was deleting the redundant path entirely rather than patching the flag collision — per the user's explicit ask to make the toggle behave like the already-working long-press path instead of debugging tap-mode in isolation.
42. **A threshold fix applied to only one of two matching code paths looks fixed but isn't.** Fixing `checkMatch()`'s threshold for the full-phrase typed-answer case didn't fix the reported symptom ("still feels like it accepts 2 characters too early") because the *per-word* partial-reveal path (`updateRevealedWords` → `checkWordInTranscript`) is a structurally separate matcher with its own default threshold, called from the same typed-input handler. When a fuzzy-match complaint persists after fixing the obvious call site, grep for every function that consumes the same input string, not just the first one found.
43. **Guard a self-filtering LLM pass against both under- and over-correction, not just failure.** A second model call asked to "clean up" a list can fail in three shapes that all need independent handling: the HTTP/parse layer can error (catch and fall back), the model can invent phrases that were never in the input (intersect the result against the original candidate set), and the model can be too aggressive and cut the list below what's usable (compare filtered-size against a minimum floor and fall back to unfiltered rather than under-deliver). Treating "the filter call succeeded" as equivalent to "the filter result is safe to use" misses the latter two.

## Session: 2026-08-04 (part 2)

### Fixed category long-press delete, wiped stale prod categories, then fixed generation reliability
User reported the long-press-to-delete on category pills "seems broken." Root cause: a prior session (`fb3f9f0`) had added an IP-based "only the creator can delete" check on `DELETE /api/categories/{name}`, but the frontend never checked the delete response before removing the pill from the screen — so a 403 (creator IP mismatch, easily triggered by mobile carrier NAT/wifi-cellular handoff/IPv4-IPv6 flip-flopping behind Cloudflare) looked like a successful delete client-side while the category silently survived server-side and reappeared on reload. Removed the IP-ownership check entirely (this is a small family game, not a multi-tenant system needing per-IP auth, and categories already auto-expire after 7 days) and made the frontend check `res.ok` before removing the pill, alerting on failure instead. Also wiped all 16 categories then in production (`data/state.json`) so the user could test the (separately upgraded, previous session) Sonnet-based generation pipeline against a clean slate.

### AI generation reliability: adaptive thinking silently eating the whole token budget
Immediately after the above, user reported "80s bands" and "80s movies" returned "No phrases generated" while "animals" worked. Root cause, found via added diagnostic logging: Claude Sonnet 5 runs **adaptive thinking on by default** when the `thinking` param is omitted (a model-migration detail missed in the prior session's Haiku→Sonnet upgrade). For topics the model deliberates over more — real movie/band names, likely triggering extra caution — the thinking block alone consumed the entire 1024-token `max_tokens` budget, leaving `stop_reason: max_tokens` with zero actual answer text. Separately, the response-parsing code was already blindly reading `content[0]` for the answer text, which is wrong the moment a `thinking` block precedes the `text` block (a second, related bug from the same model-migration gap). Fixes:
- Added `extractClaudeText()` helper that finds the block by `type == "text"` instead of assuming position; applied to all three Claude-response call sites (generation, filter pass, clue generation).
- Explicitly disabled thinking (`thinking: {type: "disabled"}`) on both generate-phrases calls — this is a plain list-generation task with no multi-step reasoning need — and bumped `max_tokens` from 1024 to 2048 as headroom.
- Added warn-level diagnostic logging (`stop_reason` + raw content snippet) whenever a generation call produces zero phrases, so the next silent-empty-result bug doesn't require a fresh round of temporary debug code to diagnose.
- Added "Can take up to 20 seconds" to the add-category dialog, since two sequential Claude calls (generate + self-filter) plus real-world latency add up.

### Theme emoji for celebrations (mid-session redesign after a requirements miss)
User asked to replace the default 🔥 celebration emoji with something theme-relevant when available. First implementation generated ONE emoji per *category* (returned alongside the phrase list, stored with the category, shown on the pill and used as the celebration fallback) — but the user clarified after using it that they wanted emoji to be **phrase-specific** (e.g. "ice cream sundae" → 🍨), not category-wide, while being fine with category-specific for the category pill itself. Reworked to a three-tier fallback: **phrase-specific emoji → category emoji → fire**.
- Prompt changed to ask for `{"emoji": "🦕", "phrases": [{"phrase": "...", "emoji": "..."}]}` — most phrases get `""` (empty) since the model is told most won't have an obviously fitting emoji; only genuinely specific matches get one.
- `CachedCategory`/`CategoryEntry`/`GeneratePhrasesResponse` all gained a `phraseEmojis: Map<String, String>` field alongside the existing `emoji: String`, persisted through `state.json` and threaded through `/api/categories` GET/POST like the category emoji already was.
- Frontend: new `phraseEmojis` object (phrase text → emoji) alongside the existing `customCategoryEmoji`, reset/set at every point the latter already was (7 call sites — `selectTopic`, `selectCatPill`, delete-active-category, `customizeSave`, `customizeClear`, plus the score-refresh POST). Only `showCelebrationLocal` (mid-round, tied to one just-guessed phrase) uses the phrase-specific lookup; `showGameSummary` (end-of-game total) stays category-level, since there's no single "current phrase" at that point.
- Verified live: "desserts" category generation gave 12 of 20 phrases a specific fitting emoji (ice cream sundae→🍨, apple pie→🥧, banana split→🍌, etc.) and correctly left the rest empty; browser-executed test confirmed all three fallback tiers fire in the right priority order.

### Code review pass (self-requested) surfaced two more real gaps
Asked to review the day's diff for additional bugs before moving on. Found and fixed:
- **Resilience-fallback gap**: the object-shape parser (added earlier this session for the emoji feature) only fell back to bare-array parsing when the *entire* response failed to parse as a JSON object. If Claude had ever returned a validly-shaped object missing the `phrases` key, the code would've silently returned zero phrases instead of falling through to the bare-array attempt — undercutting the fallback's stated purpose. Fixed by explicitly throwing when the `phrases` key is absent, routing into the same fallback path as a full parse failure.
- **Inconsistent emoji length caps**: the AI-generated category emoji was capped at `.take(8)` (UTF-16 units), but a client-submitted emoji via `POST /api/categories` had no cap at all. Unified both under a new `MAX_EMOJI_CHARS = 16` constant — 16 rather than 8 because compound/ZWJ-joined emoji (family, profession + skin tone) can run 8-11 UTF-16 units for a single glyph, and 8 risked truncating mid-sequence into a broken/mojibake character.

### Key Learnings
44. **Model-migration guides call out default-behavior changes for a reason — re-check them when a "harmless" model swap breaks unrelated things.** The Haiku→Sonnet upgrade earlier this session was tested against a few categories and looked fine, but Sonnet 5's adaptive-thinking-on-by-default was a silent behavior change that only manifested as a total failure for topics the model deliberated over more heavily. A model swap should prompt a deliberate check of "what's different by default on the new model," not just a smoke test of the happy path.
45. **When the user corrects a just-shipped feature's scope, prefer reworking over bolting on.** The initial category-level-only emoji wasn't wrong, exactly — it was a reasonable reading of an ambiguous request — but once corrected ("phrase specific, not category specific"), the right move was restructuring the data shape (`emoji: string` → `phraseEmojis: Map<string,string>` alongside the existing category emoji) rather than layering a second mechanism awkwardly on top. The category-level emoji stayed, because the user explicitly said they didn't mind it "for the category" — worth listening for which parts of a correction are a full reversal versus a scope narrowing.
46. **A "resilience fallback" that only triggers on total parse failure isn't resilient to structurally-valid-but-wrong-shape responses.** The object-vs-bare-array fallback added for the emoji feature checked "did `JsonObject.serializer()` throw" but not "did the object actually have the key we need" — two different failure modes that look identical in intent (the model didn't give us what we expected) but only one of which was actually handled. Worth asking "what does 'valid but wrong' look like here" whenever writing a fallback keyed on exceptions alone.

## Session: 2026-08-04 (part 3)

### Perceived latency pass: voice detection + celebration
User's son reported voice detection and the win celebration felt "a bit slow." Investigated with fresh eyes rather than assuming nothing was fixable in "just a web app."

- **Real bug found**: `showCelebrationLocal`'s closing `setTimeout` used the `CELEBRATION_DELAY` constant directly instead of the `delay` variable it had already computed (`isManual ? 600 : CELEBRATION_DELAY`) — so in local mode, manually pressing "Got It" waited the full non-manual delay instead of the intended shorter one. The online-mode equivalent (`showCelebration`) had this right; the local-mode copy had drifted. Fixed both by using `delay` consistently.
- **`CELEBRATION_DELAY` trimmed 1200ms → 800ms.** Verified first that this delay gates nothing functional: `localModeClaimVictory()` calls `nextPhrase()` synchronously right after `showCelebrationLocal()`, so the next phrase's text, `hasClaimedVictory` reset, and speech-recognition baseline are all already live *underneath* the overlay by the time it's animating — the delay is purely how long the confetti/score screen visually blocks the screen. No comment justified the original 1200ms.
- **Trimmed particle/confetti counts** (50→28 particles, 60→30 confetti) in both celebration functions — same visual effect, less DOM/paint work per round, which matters most on the kind of phone this actually gets played on.
- **Adaptive mic-restart debounce** (the more speculative, higher-value fix): Android's speech recognizer can fire `onend` mid-game even with `continuous: true`, and a prior session had added a flat 2000ms debounce before restarting to stop a rapid onend→start loop from spamming Chrome's start-of-recognition "ding." That flat delay meant *every* `onend` — including an ordinary silence-triggered one between phrases — cost 2s of dead mic. Made it adaptive: track `recognitionStartedAt`; if `onend` fires within 1500ms of the last `start()` (the actual signature of the rapid-cycle bug), keep the full 2000ms debounce; otherwise restart in 150ms. Preserves the original ding-suppression for the failure mode it targeted while cutting typical dead-mic time by ~90%. Explicitly flagged to the user as untestable from here (no real Android hardware) and worth specifically watching for regressed "ding spam."
- **Verified via `setTimeout` interception, not wall-clock timing**: an initial verification attempt measured actual elapsed time in a background browser tab and got nonsense numbers (1866ms/1003ms for supposedly-600ms/800ms delays) — Chrome throttles `setTimeout` in hidden/unfocused tabs to ~1/sec, which fully explained the skew. Switched to monkey-patching `window.setTimeout` to capture the requested delay value directly, sidestepping the throttle entirely.

### "Most fun way to start" defaults, for sending the game to a new friend
Two follow-up requests aimed at first-visit experience:
- **Mic now defaults to on, tilt-to-reveal now defaults to off** for players with no saved preference. Both were previously the opposite of what a new visitor got (`orientation_pref` unset → tilt on; `mic_pref` unset → mic off) — inverted both conditions to check for the opposite explicit value (tilt: only on if saved `=== 'on'`; mic: on unless saved `=== 'off'`), so an explicit prior choice is still always respected. Bonus: defaulting tilt off means a new visitor's very first game no longer triggers the iOS motion-permission prompt.
- **Homepage now auto-selects the first category pill** (excluding the Random pill and the "+" add-category pill) instead of leaving nothing selected when there's no saved category preference — including as a fallback when a previously-saved category pill no longer exists (e.g. was deleted). Since `categoriesOfTheDay` is a global, family-shared cache rather than per-visitor, a brand-new friend opening the link sees whatever category the family most recently played/created already pre-selected, rather than the generic hardcoded "Classic" idiom deck or having to click into "Random" themselves.
- **Added a subtle "More games: Totwords" link** near the version number in the bottom-right corner, pointing to `/flashcards` (relative path, same origin — avoids hardcoding the domain).

### Key Learnings
47. **A perceived-slowness complaint can be a mix of one real gate, several small stacked costs, and one perception-only cost — treat each differently.** Of the four latency changes made, only the adaptive mic-restart delay was gating anything a player was actually waiting on (a dead microphone); the celebration delay and particle count were purely visual/paint cost with the game state already advanced underneath, and the `showCelebrationLocal` bug was a straightforward regression. Diagnosing "make X faster" well means classifying each contributor as functional-block vs. visual-block vs. plain bug before deciding how aggressively to change it.
48. **When a fixed debounce/cooldown value was tuned against real hardware behavior you can't reproduce, don't just shrink the number — change what triggers it.** The 2000ms mic-restart debounce existed to suppress a specific failure signature (rapid onend-right-after-start on some Android builds), not to slow down every restart. Keying the delay off *how that specific onend behaved* (time since last start) rather than picking a smaller flat number preserves the original protection for the case it was built for while fixing the common case it was incidentally also slowing down.
49. **Background/unfocused browser tabs throttle `setTimeout` to ~1/sec — wall-clock verification of short delays needs a focused tab or a different measurement method.** Measuring actual elapsed time for a 600ms/800ms delay in a backgrounded automation tab produced numbers clamped near the 1-second throttle floor, which looked like a bug in the fix rather than an artifact of the test. Intercepting `setTimeout` itself to read the requested delay argument verifies the code path without fighting browser tab-visibility power-saving behavior.

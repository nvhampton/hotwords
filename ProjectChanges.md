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

### Key Learnings
26. **AWS bills for every attached public IPv4, EIP or not** — Since Feb 2024, the $0.005/hr charge applies to auto-assigned public IPv4s too. "Elastic IPs: none" is not the same as "no public IPv4."
27. **`modify-network-interface-attribute --no-associate-public-ip-address` works on running ENIs** — Older AWS docs imply you need to swap the ENI or relaunch the instance to drop an auto-assigned public IPv4. Not anymore: the API call releases it in-place, no downtime. Try this first before any ENI surgery.
28. **Subnet `MapPublicIpOnLaunch` is launch-time only** — Setting it to false does NOT affect existing ENIs across stop/start. They keep their per-ENI "associate public IP" attribute baked in at launch, and a fresh public IP is assigned on every start until you flip the ENI attribute.
29. **Bash arrays beat option strings for ssh/scp wrappers** — `SSH_OPTS="-o Foo=$X"` used unquoted (`scp $SSH_OPTS …`) breaks the moment `$X` contains a space, because re-expansion does word-splitting but not quote-removal. Use `SSH_OPTS+=(-o "Foo=$X")` and `scp "${SSH_OPTS[@]}" …`.
30. **EC2 Instance Connect Endpoint takes ~5 minutes to provision** — Creation returns immediately but `State` stays `create-in-progress` for around 5 min before becoming `create-complete`. Plan automation around this; don't assume it's ready right after the API call.

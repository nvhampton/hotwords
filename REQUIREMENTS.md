# Hotwords - Requirements & Design Document

**Version:** 1.1.0
**Last Updated:** 2026-02-20

## Overview

Hotwords is a multiplayer word/phrase guessing game. Players see a phrase briefly, then must recall and speak it (or confirm they got it). The game supports two modes: Online (WebSocket-based multiplayer) and Pass This Device (local multiplayer on a single device).

---

## Game Modes

### Mode 1: Online Mode (Default)

Players connect to a shared "room" via WebSocket. All players in a room see the same phrase and compete to speak it first.

**Flow:**
1. User lands on page → auto-connects to a random room
2. User can change room name or click "Random" for a new room
3. User enters their display name
4. User clicks "Start Listening" → speech recognition begins, 40-second timer starts
5. Phrase displays for 2.5 seconds, then is redacted (hidden)
6. User speaks the phrase → fuzzy matching checks similarity
7. At 85%+ match → victory claimed, server broadcasts to all players
8. Celebration animation plays, new phrase appears
9. Timer expires → game ends, score shown

**Server Responsibilities:**
- Maintain room membership (sessions per room)
- Store current phrase per room
- Store cumulative score per room
- Broadcast events: `NEW_WORD`, `ROUND_WON`, `WORD_SKIPPED`, `SCORE_UPDATE`

**Client Messages:**
- `CLAIM_VICTORY` - Player matched the phrase
- `SKIP_WORD` - Player wants to skip current phrase

### Mode 2: Pass This Device Mode

A single device is shared among multiple players sitting together. No server connection needed.

**Flow:**
1. User toggles "Pass This Device Mode" checkbox
2. WebSocket disconnects, player setup UI appears
3. User adds 2+ player names (persisted to localStorage)
4. User clicks "Start Game"
5. Interstitial shows: "Pass the device to [Player Name]"
6. Player clicks "I'm Ready!"
7. Phrase appears (controlled by orientation/tap - see below)
8. Player memorizes phrase, then:
   - Clicks "Got It!" → score increments, celebration plays
   - Clicks "Skip" → no score change
9. Next player interstitial appears
10. Cycle continues indefinitely (no win condition currently)

**Word Visibility Control:**
- **Mobile (with accelerometer):** Phrase visible only when device is flat (screen facing up). Tilting upright hides it.
- **Desktop (no accelerometer):** Tap the word area or orientation indicator to toggle visibility.

---

## State Management

### Global State Variables

| Variable | Type | Description |
|----------|------|-------------|
| `currentWord` | string | The active phrase to guess |
| `ws` | WebSocket | Connection to server (null in Pass Mode) |
| `recognition` | SpeechRecognition | Browser speech API instance |
| `isListening` | boolean | Whether speech recognition is active |
| `hasClaimedVictory` | boolean | Prevents double-claiming same phrase |
| `currentRoomId` | string | Active room identifier |
| `gameActive` | boolean | Whether timer is running (Online Mode) |
| `localScore` | number | Points earned this session |
| `timeRemaining` | number | Seconds left in round |
| `isCelebrating` | boolean | Whether celebration overlay is showing |

### Pass Mode State

| Variable | Type | Description |
|----------|------|-------------|
| `passMode` | boolean | Pass Mode toggle state |
| `passModeActive` | boolean | Game in progress (after Start Game) |
| `passModePlayers` | string[] | List of player names |
| `passPlayerScores` | object | Map of player name → score |
| `currentPlayerIndex` | number | Index of current player |
| `orientationEnabled` | boolean | Using device orientation API |
| `tapToRevealEnabled` | boolean | Using tap fallback |
| `deviceIsFlat` | boolean | Current orientation state |

### Server State (Kotlin)

| Variable | Type | Description |
|----------|------|-------------|
| `rooms` | ConcurrentHashMap<String, Set<Session>> | Room → connected sessions |
| `roomScores` | ConcurrentHashMap<String, Int> | Room → cumulative score |
| `roomWords` | ConcurrentHashMap<String, String> | Room → current phrase |
| `gameWords` | List<String> | 60 available phrases |

---

## User Interface Components

### Main Screen Elements

| Element | Online Mode | Pass Mode |
|---------|-------------|-----------|
| Mode Toggle | Visible | Visible |
| Room Input | Visible | Hidden |
| Player Name Input | Visible | Hidden |
| Player Setup | Hidden | Visible (before game) |
| Current Player Indicator | Hidden | Visible (during game) |
| Orientation Indicator | Hidden | Visible (during game) |
| Word Display | Visible | Visible |
| Score Display | Visible | Hidden |
| Timer | Visible | Hidden |
| Transcript | Visible | Hidden |
| Local Scoreboard | Hidden | Visible (during game) |
| Listen/Got It Button | "Start Listening" | "Got It!" |
| Skip Button | "Skip Word" | "Skip" |

### Overlays

1. **Celebration Overlay** - Full-screen, shows winning phrase and player name, confetti/particles
2. **Pass Interstitial** - Full-screen, shows next player name, "I'm Ready" button
3. **Close Match Overlay** - Shows "CLOSE!" when 80-84% match (Online Mode only)

---

## Phrase Matching Logic

### Algorithm
1. Normalize both target phrase and spoken transcript (lowercase, trim)
2. Check for exact substring match first
3. Use sliding window over transcript words to find best phrase match
4. Calculate Levenshtein distance-based similarity percentage
5. If any window achieves ≥85% similarity → match

### Similarity Thresholds
- **< 70%** - No indicator
- **70-79%** - Yellow "close" indicator in transcript area
- **80-84%** - Full-screen "CLOSE!" overlay (Online Mode)
- **≥ 85%** - Victory claimed

---

## Technical Architecture

### Frontend (Single-Page HTML)
- ~1800 lines of HTML/CSS/JS in one file
- No build system or framework
- Uses Web Speech API for voice recognition
- Uses DeviceOrientation API for tilt detection
- LocalStorage for player name persistence

### Backend (Kotlin/Ktor)
- WebSocket server on port 8080
- In-memory state (no persistence)
- JSON message protocol via kotlinx.serialization
- Static file serving for index.html

### Deployment
- Docker multi-stage build (Gradle → JRE)
- Caddy reverse proxy for HTTPS
- EC2 instance with ARM architecture

---

## Message Protocol

### Client → Server

```json
{ "type": "CLAIM_VICTORY", "player": "PlayerName" }
{ "type": "SKIP_WORD", "player": "PlayerName" }
```

### Server → Client

```json
{ "type": "NEW_WORD", "word": "Phrase here" }
{ "type": "ROUND_WON", "player": "WinnerName", "score": 5 }
{ "type": "WORD_SKIPPED", "player": "SkipperName" }
{ "type": "SCORE_UPDATE", "score": 5 }
```

---

## Known Issues & Technical Debt

### Architecture
1. **Monolithic HTML file** - 1800+ lines mixing CSS, HTML, JS. Hard to maintain.
2. **Duplicated word list** - Same 60 phrases exist in both client and server.
3. **No state machine** - Game state transitions are scattered across event handlers.
4. **Mixed concerns** - Online mode and Pass Mode logic intertwined.

### Functionality
5. **No game end in Pass Mode** - Game runs forever until page refresh.
6. **No room cleanup** - Empty rooms persist in server memory.
7. **No reconnection handling** - Player loses context on disconnect.
8. **Score is room-scoped** - No individual player scores in Online Mode.
9. **Timer only in Online Mode** - Pass Mode has no time pressure.

### UX
10. **Orientation detection unreliable** - Falls back to tap, but messaging unclear.
11. **No audio feedback** - All feedback is visual only.
12. **No accessibility** - No ARIA labels, keyboard navigation limited.
13. **Version caching issues** - Users see stale versions without hard refresh.

---

## Potential Improvements

### Short-term
- Add cache-busting to static assets
- Add "End Game" button to Pass Mode
- Add audio cues for victory/close match
- Split JS into modules

### Medium-term
- Implement proper state machine for game flow
- Add individual player scores in Online Mode
- Add configurable game settings (timer, threshold)
- Add phrase categories/difficulty levels

### Long-term
- Refactor to SPA framework (React/Vue/Svelte)
- Add user accounts and persistent scores
- Add custom phrase lists
- Add spectator mode

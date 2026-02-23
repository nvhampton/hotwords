# Hotwords - Requirements & Design Document

**Version:** 0.9.0

## Overview

Hotwords is a multiplayer word/phrase guessing game. Players see a phrase briefly, then must recall and speak it. The game supports two modes:
- **Local Mode**: Hot potato style - pass one device between players
- **Online Mode**: Distributed hot potato - one describer gives clues, others guess

---

## Game Modes

### Local Mode (Hot Potato)

A single device is shared among 2+ players. The game runs on a continuous timer - whoever is holding when time runs out loses.

**Flow:**
1. Select "Local Mode" in lobby, add 2+ player names
2. Click "Start" to begin 60-second countdown
3. First player sees phrase (press & hold to reveal)
4. Player says the phrase or clicks "Got It!"
5. On success: celebration shows score + next player, then rotates
6. "Skip" gets new phrase but stays with same player (hot potato!)
7. Timer expires → game over, current player loses

**Word Progress:**
- Phrase displays as `Player: #### ### #####`
- Individual words reveal as they're detected in speech
- All words revealed = automatic victory

**Controls:**
- Press & hold word area (or Space) to reveal phrase
- "Got It!" button (or Enter) to claim victory
- "Skip" button (or Tab) for new phrase

### Online Mode (Distributed Hot Potato)

Players connect to shared rooms via WebSocket. One player is the "hot" describer while others are guessers.

**Roles:**
| Role | Sees | Does | Speech Recognition |
|------|------|------|-------------------|
| Hot (Describer) | Full phrase | Gives verbal clues | Monitors for slips |
| Guesser | `####` + reveals | Speaks guesses | Matches words |

**Flow:**
1. Select "Online Mode" in lobby, enter name and room
2. Click "Start" to connect to room
3. First player to join becomes the describer
4. **Describer**: Sees full phrase, gives verbal clues (can't say the words!)
5. **Guessers**: See `####` pattern, speak guesses into microphone
6. Words reveal as guessers match them
7. Full phrase matched → victory, describer rotates to next player
8. Skip → same describer, new phrase (they take the penalty!)

**Slip Penalties:**
- Describer's speech is monitored
- If describer says a forbidden word → that word is revealed (helps guessers!)
- If describer says the entire phrase → automatic fail, turn rotates

**Player Management:**
- Players tracked with 15-second heartbeat TTL
- Player list shows all connected players with role badges
- 🔥 badge = current describer
- → indicator = next describer

---

## UI Components

### Lobby Screen
- Title: "🔥 Hotwords 💬"
- Two mode buttons that expand on selection:
  - Local Mode → Player name inputs
  - Online Mode → Name + room name input
- Selected mode button changes to "▶ Start"

### Game Screen (Local Mode)
- Word progress: `Player: #### ### #####`
- Word display area (press & hold to reveal)
- Timer countdown (red when < 8s)
- Score display
- Buttons: "Got It! [Enter]", "Skip [Tab]"
- Back button to return to lobby

### Game Screen (Online Mode)
- **Player panel**: List of players with role badges
- **Role indicator**: "🔥 DESCRIBE IT!" or "🎤 GUESS THE PHRASE"
- **Word display**: Full phrase (describer) or `####` pattern (guessers)
- **Word progress**: Shows revealed words for guessers
- Timer and score
- Buttons vary by role:
  - Describer: Listen (monitors), Skip
  - Guesser: Listen, Got It!
- Transcript with speech feedback

### Celebrations
- **Local**: Shows score count with 🔥 + "Next: [Player]"
- **Online**: Shows phrase + "[Player] got it!"
- Confetti and particle effects

### Penalty Animations
- **Slip**: Brief red flash when describer says a forbidden word
- **Fail**: Shake + red background when describer says entire phrase

---

## Technical Details

### Speech Recognition
- Uses Web Speech API (Chrome/Edge)
- Continuous recognition during gameplay
- Fuzzy matching with Levenshtein distance
- 85% similarity threshold for matches
- Individual word detection for progressive reveals

### Player Tracking (Online Mode)
- Players identified by persistent localStorage ID
- Heartbeat sent every 10 seconds
- Server removes players after 15 seconds of no heartbeat
- Hot player index adjusts when players leave

### State Management
- Player names persisted to localStorage
- Player ID persisted to localStorage
- Room state maintained on server per room
- Skip rotates describer (distributed hot potato)

### Keyboard Shortcuts (Local Mode)
| Key | Action |
|-----|--------|
| Space (hold) | Reveal phrase |
| Enter | Got It! |
| Tab | Skip |

---

## Message Protocol

### Client → Server
| Type | Fields | Purpose |
|------|--------|---------|
| `SET_NAME` | player, playerId | Join room with identity |
| `HEARTBEAT` | - | Keep connection alive |
| `WORD_MATCH` | wordIndex, player | Guesser matched a word |
| `CLAIM_VICTORY` | player | Full phrase matched |
| `SKIP_WORD` | player | Skip and rotate turn |
| `DESCRIBER_SLIP` | wordIndex, player | Said a forbidden word |
| `DESCRIBER_FAIL` | player | Said entire phrase |

### Server → Client
| Type | Fields | Purpose |
|------|--------|---------|
| `PLAYER_LIST` | players[], hotPlayerIndex | Room roster update |
| `NEW_WORD` | word, revealed[] | New phrase to play |
| `WORD_PROGRESS` | revealed[] | Updated reveal state |
| `ROUND_WON` | player, score | Victory notification |
| `WORD_SKIPPED` | player | Skip notification |
| `DESCRIBER_SLIPPED` | player, word, wordIndex | Penalty notification |
| `DESCRIBER_FAILED` | player | Full fail notification |
| `SCORE_UPDATE` | score | Room score (on join) |

---

## Tech Stack

### Frontend
- Single HTML file (~2500 lines)
- Vanilla JavaScript (no framework)
- CSS animations for celebrations/penalties
- Web Speech API
- DeviceOrientation API

### Backend
- Kotlin 1.9.22 / JVM 21
- Ktor 2.3.7 with Netty
- WebSocket for real-time multiplayer
- kotlinx.serialization for JSON
- Coroutine-based TTL cleanup
- In-memory state (no persistence)

### Deployment
- Docker multi-stage build
- Caddy reverse proxy for HTTPS
- AWS EC2 (ARM architecture)

---

## File Structure

```
hotwords/
├── REQUIREMENTS.md          # This file
├── CLAUDE.md               # AI assistant instructions
├── hotwords/
│   ├── build.gradle.kts    # Gradle build config
│   ├── Dockerfile          # Multi-stage Docker build
│   ├── src/main/
│   │   ├── kotlin/com/example/
│   │   │   └── Application.kt    # Ktor server + WebSocket
│   │   └── resources/
│   │       ├── application.conf  # Ktor config
│   │       └── static/
│   │           └── index.html    # Full frontend
│   └── deploy/
│       ├── docker-compose.yml
│       ├── Caddyfile
│       ├── setup.sh
│       └── deploy.sh
```

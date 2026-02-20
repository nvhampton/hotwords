# Potential Updates

## Feature 1: Pass This Device Mode

### Overview
A local multiplayer mode where a single device is passed between players, rather than each player having their own device connected to a room.

### User Flow
1. Player enables "Pass This Device" mode from the game screen
2. Player enters the number of participants (or adds player names)
3. Game tracks which player's turn it is locally
4. After each round (win or skip), the UI prompts to pass the device to the next player
5. Scores are tracked per-player locally on the device

### UI Changes
- Add toggle/button: "Pass This Device Mode" on the room join or game screen
- Player setup screen to enter number of players or player names
- "Pass to [Next Player]" interstitial screen between rounds
- Local scoreboard showing all players' scores
- Current player indicator during gameplay

### Technical Considerations
- No WebSocket needed in this mode (fully local)
- Store player list and scores in local state
- Could optionally sync final scores to a room for spectators
- Timer behavior: reset per player or continuous?

### Open Questions
- Should voice recognition still be used, or is this a "show and guess" party mode?
- How to handle player order - fixed rotation or winner goes again?
- Should there be a "game end" condition (first to X points, or fixed rounds)?

---

## Feature 2: Device Orientation Word Reveal

### Overview
When in "Pass This Device" mode, the word is only visible when the phone is held level (parallel to the ground, screen facing up). When the device is tilted upright (normal viewing position), the word is hidden.

### User Flow
1. Device is passed to next player
2. Player holds phone flat (like placing it on a table) to see the word
3. Player memorizes the word, then tilts phone upright to hide it
4. Other players cannot see the word while it's being held normally
5. Player gives clues or acts out the word (depending on game variant)

### UI Changes
- Orientation indicator showing when word will be revealed
- Smooth transition between hidden/revealed states
- Visual feedback: "Tilt device flat to see word" instruction
- Optional: countdown timer while word is visible in flat position

### Technical Implementation
```javascript
// Use DeviceOrientation API
window.addEventListener('deviceorientation', (event) => {
  const beta = event.beta;   // Front-to-back tilt (-180 to 180)
  const gamma = event.gamma; // Left-to-right tilt (-90 to 90)

  // Device is "flat" when beta is near 0 (or 180) and gamma is near 0
  const isFlat = Math.abs(beta) < 30 && Math.abs(gamma) < 30;

  // Alternative: check if screen is facing up
  // beta ~= 0 means screen facing up
  // beta ~= 90 means screen facing user (upright)

  setWordVisible(isFlat);
});
```

### Permission Requirements
- iOS 13+ requires user permission for DeviceOrientation
- Need to prompt user and handle permission denial gracefully
- Fallback: manual "reveal/hide" button if orientation not available

### Thresholds to Tune
| Position | Beta (approx) | Behavior |
|----------|---------------|----------|
| Flat (screen up) | -30° to 30° | Word visible |
| Tilted/Upright | 30° to 150° | Word hidden |
| Face down | 150° to 180° | Word hidden |

### Edge Cases
- Device on table vs held flat in hand (slight wobble)
- Hysteresis to prevent flicker at threshold boundaries
- Landscape vs portrait orientation
- Tablets vs phones (different natural holding positions)

### Open Questions
- How long should the word be visible before auto-hiding? (Or purely orientation-based?)
- Should there be a "lock" feature to keep word visible temporarily?
- Audio/haptic feedback when word reveals/hides?

---

## Implementation Priority

| Feature | Complexity | Dependencies |
|---------|------------|--------------|
| Pass This Device Mode | Medium | None |
| Orientation Word Reveal | Medium | DeviceOrientation API, Pass Mode |

Recommendation: Implement Feature 1 first as the foundation, then add Feature 2 as an enhancement to that mode.

# Online Custom Phrases: Host System

## Problem

Custom/AI-generated phrases are entirely client-side and ignored in online mode. The server controls all phrase selection via built-in themes (`topic:animals`, etc.) and there's no mechanism to send a custom phrase list to the server. Players who enter custom phrases or use AI generation see those phrases in local mode only.

## Design

### Host Concept

- First player in a room becomes the **host** (tracked server-side as `hostPlayerId` on `RoomState`)
- If the host disconnects (TTL expires or leaves), the longest-tenured remaining player is auto-promoted
- Host is shown in the player list and ready overlay with a crown badge
- Only the host's theme/phrase selection affects the room's phrase pool
- When a new host is assigned, custom phrases clear — new host can set their own or the room falls back to defaults

### Custom Phrases Transport

- New WebSocket message type: `SET_PHRASES` — host sends their custom phrase list (the AI-padded array) to the server
- Server stores per-room in `roomCustomPhrases: ConcurrentHashMap<String, List<String>>`
- `getWordForRoom()` priority: custom phrases > theme > defaults
- Custom phrases are cleared when host changes or room empties

### Message Protocol Additions

**Client -> Server:**
- `SET_PHRASES` — `{ type: "SET_PHRASES", phrases: string[] }` — host uploads custom phrase list

**Server -> Client:**
- `PLAYER_LIST` gains `hostPlayerId: String` field so clients know who the host is
- `HOST_CHANGED` — `{ type: "HOST_CHANGED", hostPlayerId: String }` — broadcast when host changes mid-game

### UI Changes

- Crown icon next to host name in ready overlay player list
- Non-hosts see "Host picks phrases" hint in the customize area (or customize is disabled/greyed out)
- Topic picker still visible for non-hosts but only host's selection takes effect
- When promoted to host, player gets a brief toast/notification

### Server Changes (Application.kt)

1. Add `hostPlayerId: String?` to `RoomState`
2. Add `roomCustomPhrases: ConcurrentHashMap<String, List<String>>`
3. Set host on first `SET_NAME` (player join) if no host exists
4. On player disconnect/TTL cleanup: if departing player was host, promote next player by join order
5. Handle `SET_PHRASES` message: validate sender is host, store phrases
6. Update `getWordForRoom()`: check `roomCustomPhrases[roomId]` first
7. Include `hostPlayerId` in all `PLAYER_LIST` broadcasts
8. Clear custom phrases on host change and room cleanup

### Frontend Changes (index.html)

1. Track `hostPlayerId` from `PLAYER_LIST` messages
2. Show crown badge next to host in ready overlay and player list
3. When host and custom phrases are active: send `SET_PHRASES` after AI generation completes
4. When host and topic is selected: send theme via existing `SET_NAME`/`NEW_ROUND` flow (already works)
5. When not host: disable/hint the customize overlay and topic picker
6. Handle `HOST_CHANGED` message: update UI, enable/disable customize controls
7. On Play Again (`NEW_ROUND`): if host has custom phrases, re-send `SET_PHRASES`

### getWordForRoom() Updated Logic

```kotlin
fun getWordForRoom(roomId: String): String {
    // 1. Custom phrases (host-uploaded) take priority
    val custom = roomCustomPhrases[roomId]
    if (custom != null && custom.isNotEmpty()) return custom.random()

    // 2. Theme-based phrases
    val theme = roomThemes[roomId]
    if (theme == "rko") return rkoWords.random()
    if (theme != null && theme.startsWith("rko:")) { /* existing logic */ }
    if (theme != null && theme.startsWith("topic:")) { /* existing logic */ }

    // 3. Default fallback
    return gameWords.random()
}
```

## Milestones

### Milestone 1: Server-side host tracking ✅
- [x] Add `hostPlayerId` to `RoomState`
- [x] Assign host on first player join
- [x] Re-assign host on player leave/TTL expiry (promote longest-tenured)
- [x] Include `hostPlayerId` in all `PLAYER_LIST` messages (add to `GameMessage`)
- [x] Host changes communicated via PLAYER_LIST (no separate HOST_CHANGED needed)
- [x] Clear host when room empties

### Milestone 2: Custom phrases on server ✅
- [x] Add `roomCustomPhrases` ConcurrentHashMap
- [x] Handle `SET_PHRASES` message (validate sender is host, store list)
- [x] Update `getWordForRoom()` to check custom phrases first
- [x] Clear custom phrases on host change and room cleanup
- [x] Cap phrase list size (max 200 phrases) to prevent abuse
- [x] Gate theme setting (SET_NAME, NEW_ROUND) to host only

### Milestone 3: Frontend host UI ✅
- [x] Parse `hostPlayerId` from `PLAYER_LIST` messages
- [x] Show crown/host badge next to host name in ready overlay
- [x] Show crown/host badge in player list display
- [x] Update local `isHost` state on PLAYER_LIST, show toast if promoted
- [x] `updateHostUI()` dims/enables customize controls based on host status

### Milestone 4: Frontend phrase controls for host ✅
- [x] When host + custom phrases active: send `SET_PHRASES` after AI generation (save handler)
- [x] When host + topic selected: existing theme flow works (gated server-side)
- [x] On `NEW_ROUND` as host: re-send `SET_PHRASES` if custom phrases active
- [x] Non-host: grey out customize balloon + topic picker
- [x] Non-host: customize balloon click blocked
- [x] Send `SET_PHRASES` on initial connect (server accepts only from host)
- [x] Clear button also sends empty `SET_PHRASES` to server

### Milestone 5: Testing and edge cases
- [ ] Host leaves mid-game: new host promoted, phrases fall back to defaults
- [ ] Host leaves during ready phase: new host promoted, ready overlay updates
- [ ] Room with 1 player: that player is always host
- [ ] Host reconnects (same playerId): retains host status
- [ ] SET_PHRASES from non-host: server ignores
- [ ] Empty phrase list in SET_PHRASES: server clears custom and falls back
- [ ] Phrase list >200 items: server truncates

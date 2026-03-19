# Gameplay Improvements Plan

## Feature 1: +5 Seconds on Got It
Every successful "Got It" adds 5 seconds to the timer (both local and online modes).

### Milestone
- [ ] Local mode: add 5s in `localModeClaimVictory()`
- [ ] Online mode: add 5s on ROUND_WON / successful word match
- [ ] Visual feedback: brief flash on timer when time is added

## Feature 2: Show Final Word on Game End
When timer expires, keep the current phrase visible — it's a fun reveal moment.

### Milestone
- [ ] In `endGame()`, display the final word instead of clearing it
- [ ] Style it distinctly (e.g. "The word was: ___") so it's clear the game is over

## Feature 3: Free-for-All Mode
A new online game mode where there's no describer — the phrase is hidden and everyone guesses simultaneously via speech recognition. No one sees the phrase.

### Milestone
- [ ] Server: add `freeForAll` flag to room state
- [ ] Server: in free-for-all, no hot player rotation — everyone sends WORD_MATCH
- [ ] Client: in free-for-all, phrase is always hidden (####), everyone listens
- [ ] Client: no "They Got It" / skip — just speech recognition matching
- [ ] Scoring: first to match gets the point, then new phrase for everyone

## Feature 4: Custom Mode Panel Expansion
The 💬 customize balloon should expand into a full panel (like Local/Online sections) instead of opening an overlay. The panel includes:
- Free-for-all toggle
- Phrase input (1-2 examples is enough)
- AI infers the category from examples and displays it on screen

### Milestone
- [ ] Replace customize overlay with expandable panel in lobby
- [ ] Add free-for-all toggle in the panel
- [ ] Phrase input: accept 1-2 examples, call `/api/generate-phrases` to infer category + generate full list
- [ ] Show inferred category name on game screen (e.g. "Category: Animals")
- [ ] Update AI prompt to return category name alongside generated phrases

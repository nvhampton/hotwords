# Test Harness for Hotwords

## Context
The codebase has zero tests — no test frameworks, no test files, nothing. All game logic lives in a single `index.html` file with inline JS. We want to add test harnesses that Claude Code can run from the CLI to verify UI features like the game summary overlay, without needing a real browser GUI or waiting 60s for timers.

## Strategy: In-page test harness + Playwright

Two pieces:
1. **In-page test harness** — A `window.__test__` object (gated behind `?test` URL param) that exposes game state and actions, bypassing mic/orientation/timer dependencies
2. **Playwright tests** — Headless browser tests that load the page with `?test`, call harness methods, and assert DOM state

## Files to Modify/Create

- **Modify**: `hotwords/src/main/resources/static/index.html` — add test harness
- **Create**: `hotwords/tests/package.json` — minimal, just `@playwright/test`
- **Create**: `hotwords/tests/playwright.config.js` — point at localhost:8080
- **Create**: `hotwords/tests/game-summary.spec.js` — tests for the summary feature
- **Create**: `hotwords/tests/run-tests.sh` — start server, run tests, stop server

---

## 1. In-page test harness (`index.html`)

Add at the bottom of the `<script>` block, before `</script>`:

```javascript
// Test harness — only active with ?test URL param
if (new URLSearchParams(window.location.search).has('test')) {
    window.__test__ = {
        getState: () => ({ gameActive, localScore, phraseHistory, currentWord, isLocalMode, currentPlayerIndex }),

        // Set up a local game without mic/orientation prompts
        setupLocalGame: (numPlayers = 2) => {
            isLocalMode = true;
            gameActive = true;
            localModePlayers = Array.from({length: numPlayers}, (_, i) => `Player ${i+1}`);
            currentPlayerIndex = 0;
            localScore = 0;
            phraseHistory = [];
            showGameScreen();
            gameScreen.classList.add('local-mode');
            updateScoreDisplay(0);
        },

        setWord: (word) => {
            currentWord = word;
            currentWords = word.split(/\s+/);
            revealedWords = currentWords.map(() => false);
            wordDisplay.textContent = word;
            hasClaimedVictory = false;
        },

        claimVictory: () => {
            phraseHistory.push({ phrase: currentWord, status: 'got-it' });
            localScore++;
            updateScoreDisplay(localScore);
            hasClaimedVictory = true;
        },

        skipWord: () => {
            phraseHistory.push({ phrase: currentWord, status: 'skipped' });
        },

        triggerEndGame: () => endGame(),

        getOverlayState: () => ({
            summary: gameSummaryOverlay.classList.contains('active'),
            leaderboard: leaderboardOverlay.classList.contains('active'),
            celebration: celebrationEl.classList.contains('active'),
        }),

        getSummaryContent: () => ({
            score: gameSummaryScore.textContent,
            phrases: [...gameSummaryPhrases.querySelectorAll('.game-summary-phrase')].map(el => ({
                text: el.textContent.trim(),
                isSkipped: el.classList.contains('skipped'),
                isGotIt: el.classList.contains('got-it'),
            })),
        }),

        dismissSummary: () => dismissGameSummary(),
        clickSummaryPlayAgain: () => gameSummaryPlayAgain.click(),
    };
}
```

Key design decisions:
- `setupLocalGame()` sets state directly instead of calling `startLocalGame()` — avoids mic/orientation permission prompts that block in headless
- `claimVictory()` / `skipWord()` just update state + phraseHistory without triggering celebrations/timers
- `triggerEndGame()` calls the real `endGame()` so we test the actual overlay logic
- Everything is gated behind `?test` so production users never see it

---

## 2. Playwright test setup

### `hotwords/tests/package.json`
```json
{
  "private": true,
  "scripts": {
    "test": "npx playwright test"
  },
  "devDependencies": {
    "@playwright/test": "^1.50.0"
  }
}
```

### `hotwords/tests/playwright.config.js`
```javascript
const { defineConfig } = require('@playwright/test');
module.exports = defineConfig({
    testDir: '.',
    testMatch: '*.spec.js',
    use: {
        baseURL: 'http://localhost:8080',
    },
    retries: 0,
    reporter: 'list',
});
```

---

## 3. Test cases (`game-summary.spec.js`)

Tests for the game summary feature:

1. **Summary overlay appears on endGame** — setup game, add phrases, trigger endGame, assert overlay has `.active`
2. **Score displays correctly** — verify score number and fire emojis in summary
3. **Got-it phrases render with teal/checkmark** — verify `.got-it` class on correct entries
4. **Skipped phrases render with strikethrough** — verify `.skipped` class on skipped entries
5. **Phrase order matches play order** — verify phrases appear in chronological order
6. **Play Again dismisses summary** — click Play Again, verify overlay loses `.active`
7. **Play Again resets phraseHistory** — after Play Again, verify empty array
8. **Empty game shows fallback** — endGame with no phrases, verify "No phrases played"
9. **Leaderboard appears after 5s** — wait 5s after endGame, verify leaderboard overlay active (may need to mock fetch)

---

## 4. Test runner script (`run-tests.sh`)

```bash
#!/bin/bash
# Start server in background, run Playwright tests, stop server
cd "$(dirname "$0")/.."
./gradlew run &
SERVER_PID=$!
# Wait for server to be ready
for i in $(seq 1 30); do
    curl -s http://localhost:8080 > /dev/null && break
    sleep 1
done
# Run tests
cd tests
npx playwright test "$@"
EXIT_CODE=$?
# Cleanup
kill $SERVER_PID 2>/dev/null
exit $EXIT_CODE
```

---

## 5. Installation steps

```bash
cd hotwords/tests
npm install
npx playwright install chromium --with-deps
```

---

## Verification
1. `cd hotwords && ./gradlew run` (in one terminal)
2. `cd hotwords/tests && npx playwright test` (in another)
3. Or: `./hotwords/tests/run-tests.sh` (all-in-one)
4. All tests should pass with green output

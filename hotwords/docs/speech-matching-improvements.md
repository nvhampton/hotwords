# Plan: Improve Speech Recognition Matching Accuracy

## Context
Speech recognition (Web Speech API) returns "aunt" when the user says "ant" — they're homophones. The current matching uses Levenshtein distance with a 0.85 similarity threshold. For short words like "ant" (3 chars) vs "aunt" (4 chars), similarity = 0.75, which fails the threshold. This is a **phonetic** problem — Levenshtein measures edit distance, not sound.

## Problem Analysis
- Short words are disproportionately penalized: 1 char difference on a 3-letter word = 0.75 similarity
- Homophones (ant/aunt, night/knight, write/right, their/there/they're) will never match via edit distance
- The Web Speech API itself can't be tuned — it returns what it hears

## Approach: Phonetic Matching Layer + Adaptive Threshold

### 1. Add Soundex/phonetic comparison as fallback
Add a lightweight phonetic encoding function (Soundex or simplified Metaphone) so words that *sound the same* match even if spelled differently.

In `checkWordInTranscript()`:
- Keep exact match (fast path)
- Keep Levenshtein fuzzy match at 0.85
- **Add**: if Levenshtein fails, check if phonetic codes match → accept

### 2. Lower similarity threshold for short words
For words ≤ 4 characters, a single edit drops similarity to 0.67–0.75. Use an adaptive threshold:
- Words ≤ 3 chars: threshold 0.65
- Words 4 chars: threshold 0.75
- Words ≥ 5 chars: keep 0.85

### 3. Add a homophones lookup table
A small hardcoded map of common English homophones that speech recognition confuses:
```
ant↔aunt, night↔knight, write↔right↔rite, their↔there↔they're,
to↔too↔two, flour↔flower, bear↔bare, hair↔hare, etc.
```
If the target word has a homophone and the heard word matches any variant → accept.

## Files to Modify
- `hotwords/src/main/resources/static/index.html`
  - `checkWordInTranscript()` (~line 2403): add phonetic + adaptive threshold
  - `checkMatch()` (~line 3452): same phonetic matching for full-phrase mode
  - Add `soundex()` function near `levenshteinDistance()` (~line 3420)
  - Add homophones map near similarity functions

## Verification
- Test in browser: say "ant" and verify it matches target "ant" even if speech API returns "aunt"
- Test longer words still work at 0.85 threshold
- Test that false positives don't increase noticeably (soundex is conservative)

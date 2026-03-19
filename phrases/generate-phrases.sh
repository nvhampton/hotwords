#!/bin/bash
# Generate Hotwords phrases from a seed topic using Claude CLI
#
# Usage:
#   ./generate-phrases.sh "dinosaurs"
#   ./generate-phrases.sh "summer camp" 50
#   ./generate-phrases.sh "pirates" 30 > pirate-phrases.txt
#
# Args:
#   $1 - Seed topic (required)
#   $2 - Number of phrases (optional, default 40)

set -e

TOPIC="${1:?Usage: ./generate-phrases.sh \"topic\" [count]}"
COUNT="${2:-40}"

echo "Generating $COUNT phrases for topic: $TOPIC" >&2

claude -p "Generate exactly $COUNT catch phrases for a party word game, themed around: \"$TOPIC\"

These are for a game like Catch Phrase or Taboo where one person describes and others guess.

Style guide — phrases should be:
- Sayings, expressions, or well-known references (NOT just single nouns or plain descriptions)
- The kind of thing people actually say, quote, or recognize (e.g. \"T-Rex arms\" not \"Tyrannosaurus\")
- Fun to describe with clues, funny to act out, satisfying to guess
- 1-5 words each, punchy and memorable
- Accessible to ages 9+ (no obscure references)

Think: movie quotes, slang, funny expressions, pop culture references, things people shout, iconic moments, running jokes — all loosely connected to the topic.

Format: one phrase per line, no numbering, no quotes, no bullets, no extra text."

#!/bin/bash
# Build locally and deploy to EC2
#
# Usage: ./build-and-deploy.sh [HOST] [SSH_KEY]
#
# Options:
#   1. SSH config:  ./build-and-deploy.sh hotwords
#   2. Env vars:    export HOTWORDS_HOST=... HOTWORDS_SSH_KEY=~/.ssh/key.pem
#                   ./build-and-deploy.sh
#   3. Args:        ./build-and-deploy.sh <host> <key-path>

set -e

HOST="${1:-${HOTWORDS_HOST:-184.32.87.58}}"
SSH_OPTS="-o ConnectTimeout=10"

# If a key is provided (arg or env), use it; otherwise rely on SSH config
SSH_KEY="${2:-${HOTWORDS_SSH_KEY:-$HOME/.ssh/mysecurekeypair.pem}}"
if [ -n "$SSH_KEY" ]; then
    SSH_OPTS="$SSH_OPTS -i $SSH_KEY"
    SSH_TARGET="ec2-user@$HOST"
else
    SSH_TARGET="$HOST"
fi

REMOTE_DIR="~/hotwords/hotwords/deploy"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Auto-bump patch version in build.gradle.kts
GRADLE_FILE="$PROJECT_DIR/build.gradle.kts"
CURRENT_VERSION=$(grep '^version = ' "$GRADLE_FILE" | sed 's/version = "\(.*\)"/\1/')
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"
NEW_VERSION="$MAJOR.$MINOR.$((PATCH + 1))"
sed -i.bak "s/version = \"$CURRENT_VERSION\"/version = \"$NEW_VERSION\"/" "$GRADLE_FILE" && rm -f "$GRADLE_FILE.bak"
echo "=== Version: $CURRENT_VERSION → $NEW_VERSION ==="

echo "=== Building fat JAR locally ==="
cd "$PROJECT_DIR"
./gradlew shadowJar --no-daemon

JAR="$PROJECT_DIR/build/libs/game-server.jar"
echo "JAR size: $(du -h "$JAR" | cut -f1)"

echo "=== Uploading JAR + deploy files to $HOST ==="
scp $SSH_OPTS "$JAR" "$SSH_TARGET:$REMOTE_DIR/game-server.jar"
scp $SSH_OPTS "$SCRIPT_DIR/deploy.sh" "$SCRIPT_DIR/Caddyfile" "$SSH_TARGET:$REMOTE_DIR/"

echo "=== Deploying on $HOST ==="
ssh $SSH_OPTS "$SSH_TARGET" "cd $REMOTE_DIR && chmod +x deploy.sh && ./deploy.sh"

echo ""
echo "=== Done! ==="

#!/bin/bash
# Build locally and deploy to EC2
# Usage: ./build-and-deploy.sh [EC2_HOST] [SSH_KEY]

set -e

EC2_HOST="${1:-44.228.131.174}"
SSH_KEY="${2:-$HOME/Downloads/mysecurekeypair.pem}"
EC2_USER="ec2-user"
REMOTE_DIR="~/hotwords/hotwords/deploy"
SSH_OPTS="-i $SSH_KEY -o StrictHostKeyChecking=no -o ConnectTimeout=10"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Building fat JAR locally ==="
cd "$PROJECT_DIR"
./gradlew shadowJar --no-daemon

JAR="$PROJECT_DIR/build/libs/game-server.jar"
echo "JAR size: $(du -h "$JAR" | cut -f1)"

echo "=== Uploading JAR + deploy files to EC2 ($EC2_HOST) ==="
scp $SSH_OPTS "$JAR" "$EC2_USER@$EC2_HOST:$REMOTE_DIR/game-server.jar"
scp $SSH_OPTS "$SCRIPT_DIR/deploy.sh" "$SCRIPT_DIR/Caddyfile" "$EC2_USER@$EC2_HOST:$REMOTE_DIR/"

echo "=== Deploying on EC2 ==="
ssh $SSH_OPTS "$EC2_USER@$EC2_HOST" "cd $REMOTE_DIR && chmod +x deploy.sh && ./deploy.sh"

echo ""
echo "=== Done! ==="

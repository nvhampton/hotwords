#!/bin/bash
# Hotwords Deployment Script
# Run from /opt/hotwords on the EC2 instance

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Deploying Hotwords ==="

# Check if Caddyfile has been configured
if grep -q "YOUR_DOMAIN" Caddyfile; then
    echo "ERROR: Please edit Caddyfile and replace YOUR_DOMAIN with your actual domain"
    exit 1
fi

# Check if we have the app source (for building) or just docker-compose
if [ -d "../src" ]; then
    echo "Building Docker image from source..."
    cd ..
    docker build -t hotwords:latest .
    cd "$SCRIPT_DIR"
elif [ -f "hotwords.tar" ]; then
    echo "Loading Docker image from tarball..."
    docker load -i hotwords.tar
else
    echo "ERROR: No source directory or hotwords.tar found"
    echo "Either clone the repo or copy hotwords.tar to this directory"
    exit 1
fi

echo "Starting services..."
docker-compose down --remove-orphans 2>/dev/null || true
docker-compose up -d

echo ""
echo "=== Deployment Complete ==="
echo ""
docker-compose ps
echo ""
echo "View logs with: docker-compose logs -f"

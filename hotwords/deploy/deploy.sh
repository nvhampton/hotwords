#!/bin/bash
# Hotwords Deployment Script (JAR-based, no Docker)
# Runs on the EC2 instance

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Deploying Hotwords ==="

# Check if Caddyfile has been configured
if grep -q "YOUR_DOMAIN" Caddyfile; then
    echo "ERROR: Please edit Caddyfile and replace YOUR_DOMAIN with your actual domain"
    exit 1
fi

# Check JAR exists
if [ ! -f "game-server.jar" ]; then
    echo "ERROR: game-server.jar not found"
    echo "Run build-and-deploy.sh from your local machine"
    exit 1
fi

# Install Java 21 if needed
if ! command -v java &> /dev/null || ! java -version 2>&1 | grep -q "21"; then
    echo "Installing Java 21..."
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        if [ "$ID" = "amzn" ]; then
            sudo yum install -y java-21-amazon-corretto-headless
        elif [ "$ID" = "ubuntu" ]; then
            sudo apt-get update && sudo apt-get install -y openjdk-21-jre-headless
        fi
    fi
fi

# Install Caddy if needed
if ! command -v caddy &> /dev/null; then
    echo "Installing Caddy..."
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        if [ "$ID" = "amzn" ]; then
            sudo yum install -y yum-utils
            sudo yum-config-manager --add-repo https://copr.fedorainfracloud.org/coprs/g/caddy/caddy/repo/epel-9/group_caddy-caddy-epel-9.repo
            sudo yum install -y caddy || {
                # Fallback: install from GitHub releases
                echo "Installing Caddy from GitHub..."
                CADDY_URL="https://github.com/caddyserver/caddy/releases/latest/download/caddy_2.9.1_linux_amd64.tar.gz"
                curl -fsSL "$CADDY_URL" -o /tmp/caddy.tar.gz
                sudo tar -xzf /tmp/caddy.tar.gz -C /usr/local/bin caddy
                sudo chmod +x /usr/local/bin/caddy
                rm -f /tmp/caddy.tar.gz
            }
        elif [ "$ID" = "ubuntu" ]; then
            sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https
            curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
            curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
            sudo apt-get update && sudo apt-get install -y caddy
        fi
    fi
fi

# Stop old Docker containers if they exist
if command -v docker-compose &> /dev/null; then
    echo "Stopping old Docker containers..."
    docker-compose down --remove-orphans 2>/dev/null || true
fi

# Stop existing services
echo "Stopping existing services..."
sudo systemctl stop hotwords 2>/dev/null || true
sudo systemctl stop caddy 2>/dev/null || true

# Create hotwords user if needed
if ! id -u hotwords &>/dev/null; then
    echo "Creating hotwords user..."
    sudo useradd --system --no-create-home --shell /usr/sbin/nologin hotwords
fi

# Install JAR
sudo mkdir -p /opt/hotwords
sudo cp game-server.jar /opt/hotwords/game-server.jar
sudo mkdir -p /opt/hotwords/data
sudo chown -R hotwords:hotwords /opt/hotwords

# Create systemd service for hotwords
sudo tee /etc/systemd/system/hotwords.service > /dev/null <<'EOF'
[Unit]
Description=Hotwords Game Server
After=network.target

[Service]
Type=simple
User=hotwords
EnvironmentFile=-/opt/hotwords/.env
ExecStart=/usr/bin/java -Djava.net.preferIPv6Addresses=true -jar /opt/hotwords/game-server.jar
WorkingDirectory=/opt/hotwords
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/opt/hotwords

[Install]
WantedBy=multi-user.target
EOF

# Set up Caddy config
sudo mkdir -p /etc/caddy
sudo cp Caddyfile /etc/caddy/Caddyfile

# Reload and start services
sudo systemctl daemon-reload
sudo systemctl enable hotwords
sudo systemctl start hotwords

# Wait for app to start
echo "Waiting for app to start..."
for i in $(seq 1 15); do
    if curl -s -o /dev/null http://localhost:8080/; then
        echo "App is up!"
        break
    fi
    sleep 2
done

sudo systemctl enable caddy
sudo systemctl start caddy

echo ""
echo "=== Deployment Complete ==="
echo ""
sudo systemctl status hotwords --no-pager || true
echo ""
sudo systemctl status caddy --no-pager || true
echo ""
echo "View logs with: sudo journalctl -u hotwords -f"

#!/bin/bash
# EC2 Instance Setup Script for Hotwords
# Run this once on a fresh Amazon Linux 2023 or Ubuntu instance

set -e

echo "=== Hotwords EC2 Setup ==="

# Detect OS
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$ID
else
    echo "Cannot detect OS"
    exit 1
fi

echo "Detected OS: $OS"

# Install Docker
if ! command -v docker &> /dev/null; then
    echo "Installing Docker..."
    if [ "$OS" = "amzn" ]; then
        sudo yum update -y
        sudo yum install -y docker
        sudo systemctl start docker
        sudo systemctl enable docker
    elif [ "$OS" = "ubuntu" ]; then
        sudo apt-get update
        sudo apt-get install -y docker.io
        sudo systemctl start docker
        sudo systemctl enable docker
    else
        echo "Unsupported OS: $OS"
        exit 1
    fi
    sudo usermod -aG docker $USER
    echo "Docker installed. You may need to log out and back in for group changes."
else
    echo "Docker already installed"
fi

# Install Docker Compose
if ! command -v docker-compose &> /dev/null; then
    echo "Installing Docker Compose..."
    sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
    sudo chmod +x /usr/local/bin/docker-compose
else
    echo "Docker Compose already installed"
fi

# Create app directory
echo "Creating app directory..."
sudo mkdir -p /opt/hotwords
sudo chown $USER:$USER /opt/hotwords

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Next steps:"
echo "1. Log out and back in (for docker group permissions)"
echo "2. Copy your deploy files to /opt/hotwords/"
echo "3. Edit Caddyfile to set your domain"
echo "4. Run: cd /opt/hotwords && ./deploy.sh"

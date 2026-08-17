#!/usr/bin/env bash
set -euo pipefail

# Run once on the Ubuntu Compute Engine VM as the same non-root user that
# GitHub Actions will use over SSH.

if ! command -v docker >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y ca-certificates curl gnupg
  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  sudo chmod a+r /etc/apt/keyrings/docker.gpg
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" | \
    sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
mkdir -p "$HOME/enterprise-control"

if ! command -v cloudflared >/dev/null 2>&1; then
  ARCH=$(dpkg --print-architecture)
  case "$ARCH" in
    amd64|arm64) ;;
    *) echo "Unsupported architecture for cloudflared package: $ARCH" >&2; exit 1 ;;
  esac
  curl -fsSL \
    "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${ARCH}.deb" \
    -o /tmp/cloudflared.deb
  sudo dpkg -i /tmp/cloudflared.deb
fi

CLOUDFLARED_BIN=$(command -v cloudflared)

sudo tee /etc/systemd/system/enterprise-demo-tunnel.service >/dev/null <<EOF
[Unit]
Description=Enterprise Control Quick Cloudflare Tunnel
Wants=network-online.target
After=network-online.target docker.service

[Service]
Type=simple
User=$USER
ExecStart=$CLOUDFLARED_BIN tunnel --no-autoupdate --url http://127.0.0.1:8080
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now enterprise-demo-tunnel

echo
echo "Bootstrap complete."
echo "IMPORTANT: log out and back in once so your docker group membership is active."
echo "Then verify with: docker version && systemctl status enterprise-demo-tunnel"

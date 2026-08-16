#!/usr/bin/env bash
set -euo pipefail

# One-time bootstrap for a small Ubuntu VM used by staging.
# Run as the same non-root user configured in GitHub secret VPS_USER.

if ! command -v docker >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y ca-certificates curl docker.io docker-compose-v2
  sudo systemctl enable --now docker
fi

sudo usermod -aG docker "$USER"
mkdir -p "$HOME/enterprise-control"

echo "Bootstrap complete. Sign out and back in once so Docker group membership is active."
echo "Then configure GitHub Actions secrets and set repository variable VPS_DEPLOY_ENABLED=true."

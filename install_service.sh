#!/bin/bash
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
SERVICE_NAME="swdwd.service"
OLD_SERVICE_NAME="swdd.service"
SYSTEMD_USER_DIR="${HOME}/.config/systemd/user"
SERVICE_FILE="${SYSTEMD_USER_DIR}/${SERVICE_NAME}"

echo "=========================================================="
echo " SWDW - Systemd User Service Deployment"
echo " Project Directory: $PROJECT_ROOT"
echo " Target Service:    $SERVICE_NAME"
echo "=========================================================="

# 1. Compile Rust release binary if not already compiled
echo "[1/5] Ensuring Rust backend release binary is compiled..."
if [ ! -f "$PROJECT_ROOT/target/release/swd-sink-capture" ]; then
    echo "Compiling swd-sink-capture (release mode)..."
    cargo build --release --manifest-path "$PROJECT_ROOT/backend/Cargo.toml"
else
    echo "Release binary exists: $PROJECT_ROOT/target/release/swd-sink-capture"
fi

# Ensure start.sh has execute permissions
chmod +x "$PROJECT_ROOT/backend/start.sh"

# 2. Stop and disable old swdd service if active or enabled
echo "[2/5] Stopping and disabling old $OLD_SERVICE_NAME..."
if systemctl --user is-active --quiet "$OLD_SERVICE_NAME" 2>/dev/null; then
    echo "Stopping $OLD_SERVICE_NAME..."
    systemctl --user stop "$OLD_SERVICE_NAME" || true
fi

if systemctl --user is-enabled --quiet "$OLD_SERVICE_NAME" 2>/dev/null; then
    echo "Disabling $OLD_SERVICE_NAME..."
    systemctl --user disable "$OLD_SERVICE_NAME" || true
fi

# 3. Create systemd user service directory and write swdwd.service
echo "[3/5] Installing $SERVICE_NAME to $SERVICE_FILE..."
mkdir -p "$SYSTEMD_USER_DIR"

cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=SWDW - PipeWire Audio Streaming & UDP Direct Daemon
Documentation=https://github.com/
After=pipewire.service wireplumber.service sound.target network.target
Wants=pipewire.service

[Service]
Type=simple
WorkingDirectory=${PROJECT_ROOT}/backend
ExecStart=${PROJECT_ROOT}/backend/start.sh
Restart=always
RestartSec=3
KillMode=mixed
TimeoutStopSec=5
Environment=PATH=/usr/local/bin:/usr/bin:/bin:${HOME}/.cargo/bin

[Install]
WantedBy=default.target
EOF

echo "Created service unit at $SERVICE_FILE"

# 4. Reload systemd daemon and enable/start swdwd.service
echo "[4/5] Reloading systemd user daemon and starting $SERVICE_NAME..."
systemctl --user daemon-reload
systemctl --user enable "$SERVICE_NAME"
systemctl --user restart "$SERVICE_NAME"

# 5. Check service status
echo "[5/5] Verifying $SERVICE_NAME status..."
sleep 1.5

if systemctl --user is-active --quiet "$SERVICE_NAME"; then
    echo "=========================================================="
    echo " SUCCESS: $SERVICE_NAME is running successfully!"
    echo "=========================================================="
    systemctl --user status "$SERVICE_NAME" --no-pager
else
    echo "=========================================================="
    echo " ERROR: $SERVICE_NAME failed to start!"
    echo "=========================================================="
    systemctl --user status "$SERVICE_NAME" --no-pager || true
    journalctl --user -u "$SERVICE_NAME" -n 20 --no-pager || true
    exit 1
fi

#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

echo "[SWD] Launching SWD PipeWire UDP & HTTP Recording Backend..."
if [ -f "$DIR/target/release/swd-sink-capture" ]; then
    exec "$DIR/target/release/swd-sink-capture"
elif [ -f "$DIR/../target/release/swd-sink-capture" ]; then
    exec "$DIR/../target/release/swd-sink-capture"
else
    echo "[SWD] Building release binary..."
    cargo build --release
    exec "$DIR/../target/release/swd-sink-capture"
fi

#!/bin/bash
set -e

echo ""
echo "=== GCN Voice - Certificate Setup ==="
echo ""

if ! command -v mkcert &> /dev/null; then
    echo "ERROR: mkcert is not installed."
    echo "Install it with: brew install mkcert  (macOS/Linux)"
    echo "See: https://github.com/FiloSottile/mkcert"
    exit 1
fi

echo "[1/2] Installing local CA into system trust store..."
mkcert -install

mkdir -p server/certs

echo "[2/2] Generating certificates for localhost..."
mkcert -key-file server/certs/key.pem -cert-file server/certs/cert.pem localhost 127.0.0.1 ::1

echo ""
echo "=== Done! Certificates created in server/certs/ ==="
echo ""
echo "You can now start the app:"
echo "  1. cd server && npm start"
echo "  2. cd client && npm run dev"
echo ""

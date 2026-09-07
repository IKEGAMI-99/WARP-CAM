#!/usr/bin/env bash
set -euo pipefail
OUT="${1:-warp-cam-release.jks}"
ALIAS="${2:-warpcam}"
STORE_PASS="${WARP_STORE_PASSWORD:-}"
KEY_PASS="${WARP_KEY_PASSWORD:-$STORE_PASS}"
if [[ -z "$STORE_PASS" ]]; then
  echo "Set WARP_STORE_PASSWORD first. Optionally set WARP_KEY_PASSWORD."
  exit 1
fi
keytool -genkeypair -v -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" -dname "CN=WARP CAM, O=Personal, C=JP"
echo "Created: $OUT"
echo "SIGNING_KEYSTORE_BASE64:"
base64 < "$OUT" | tr -d '\n'
echo

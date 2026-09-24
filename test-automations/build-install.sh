#!/usr/bin/env bash
# Build the CoinSafeBox debug APK for a network flavor, install it on the
# emulator and launch the app.
#
# Debug builds are used for automation because they need no keystore/signing.
#
# Usage:
#   ./build-install.sh              # testnet (default)
#   ./build-install.sh mainnet
#   ./build-install.sh testnet --no-launch
#
# Environment:
#   KK_DEVICE  adb serial (default: emulator-5554)
set -euo pipefail

cd "$(dirname "$0")/.."   # project root
DEV="${KK_DEVICE:-emulator-5554}"

NET="${1:-testnet}"
case "$NET" in
  testnet) FLAVOR=productionTestnet;  PKG=com.ultrabytecoder.coinsafebox.testnet ;;
  mainnet) FLAVOR=productionMainnet;  PKG=com.ultrabytecoder.coinsafebox.mainnet ;;
  *) echo "usage: $0 [testnet|mainnet] [--no-launch]" >&2; exit 2 ;;
esac
LAUNCH=1
for a in "$@"; do [ "$a" = "--no-launch" ] && LAUNCH=0; done

echo "==> gradlew :composeApp:assemble${FLAVOR}Debug"
./gradlew ":composeApp:assemble${FLAVOR}Debug"

APK="composeApp/build/outputs/apk/${FLAVOR}/debug/composeApp-${FLAVOR}-debug.apk"
[ -f "$APK" ] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }

echo "==> adb -s $DEV install -r $APK"
adb -s "$DEV" install -r "$APK"

if [ "$LAUNCH" = 1 ]; then
  echo "==> launching $PKG"
  adb -s "$DEV" shell am start -n "$PKG/com.ultrabytecoder.coinsafebox.MainActivity"
fi
echo "done."

#!/usr/bin/env bash
# Build the desktop (JVM) uber jar for CoinSafeBox.
#
# Always invokes the packaging task (Gradle is input-aware, so the jar reflects
# current sources). The network (testnet|mainnet) is BAKED into the jar at build
# time via -PkkNetwork, so you can produce both variants:
#
#   ./build-desktop.sh                    # testnet (default)
#   ./build-desktop.sh --network=mainnet  # mainnet
#   ./build-desktop.sh --clean            # also delete previous jar(s) first
#
# The finished jar is copied to dist/CoinSafeBox-<network>.jar so both variants
# can coexist. Run one with ./run-desktop.sh --network=<network>.
set -euo pipefail

cd "$(dirname "$0")"
JAR_DIR="composeApp/build/compose/jars"
DIST_DIR="dist"

NETWORK="testnet"
CLEAN=0
usage() { grep '^# ' "$0" | sed 's/^# //'; }
for arg in "$@"; do
  case "$arg" in
    --clean|-c)   CLEAN=1 ;;
    --network=*)  NETWORK="${arg#--network=}" ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done
case "$NETWORK" in
  testnet|mainnet) ;;
  *) echo "ERROR: --network must be 'testnet' or 'mainnet' (got '$NETWORK')" >&2; exit 2 ;;
esac

if [[ "$CLEAN" -eq 1 ]]; then
    echo "Removing previous desktop jar(s)..."
    rm -f "$JAR_DIR"/CoinSafeBox-*.jar
fi

echo "Building desktop uber jar (network=$NETWORK)..."
./gradlew -PkkNetwork="$NETWORK" :composeApp:packageUberJarForCurrentOS

# The jar is named CoinSafeBox-<network>-<os>-<version>.jar (the compose plugin
# derives the base name from nativeDistributions.packageName), so match on the
# network to avoid grabbing the other variant's jar.
JAR="$(find "$JAR_DIR" -maxdepth 1 -name "CoinSafeBox-$NETWORK-*.jar" 2>/dev/null | head -n1)"
if [[ -z "$JAR" ]]; then
    echo "ERROR: build did not produce a jar in $JAR_DIR (expected CoinSafeBox-$NETWORK-*.jar)" >&2
    exit 1
fi

# Strip stale signature files from signed dependencies (e.g. BouncyCastle)
# that would otherwise cause SecurityException at runtime.
if unzip -l "$JAR" 2>/dev/null | grep -qE 'META-INF/.*\.(SF|DSA|RSA)$'; then
    echo "Stripping stale dependency signatures..."
    zip -d "$JAR" "META-INF/*.SF" "META-INF/*.DSA" "META-INF/*.RSA" >/dev/null 2>&1 || true
fi

# Publish under a stable, network-qualified name so both variants coexist.
mkdir -p "$DIST_DIR"
OUT_JAR="$DIST_DIR/CoinSafeBox-$NETWORK.jar"
cp -f "$JAR" "$OUT_JAR"

echo ""
echo "Built: $OUT_JAR ($(du -h "$OUT_JAR" | cut -f1))"
echo "Run it with: ./run-desktop.sh --network=$NETWORK"

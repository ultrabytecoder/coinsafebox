#!/usr/bin/env bash
# Run the desktop version of CoinSafeBox from a packaged uber jar.
#
# The network is BAKED into the jar (see build-desktop.sh), so pick the variant
# with --network (default: testnet). If the jar isn't built yet, it is built.
#
#   ./run-desktop.sh                    # run testnet (builds it if missing)
#   ./run-desktop.sh --network=mainnet  # run mainnet (builds it if missing)
#
# Any other arguments are passed through to the JVM (e.g. -Dcoinsafebox.network
# can still override the baked network for dev).
set -euo pipefail

cd "$(dirname "$0")"
DIST_DIR="dist"

NETWORK="testnet"
ARGS=()
for arg in "$@"; do
  case "$arg" in
    --network=*) NETWORK="${arg#--network=}" ;;
    *) ARGS+=("$arg") ;;
  esac
done
case "$NETWORK" in
  testnet|mainnet) ;;
  *) echo "ERROR: --network must be 'testnet' or 'mainnet' (got '$NETWORK')" >&2; exit 2 ;;
esac

JAR="$DIST_DIR/CoinSafeBox-$NETWORK.jar"
if [[ ! -f "$JAR" ]]; then
    echo "Jar not found ($JAR) — building it..."
    ./build-desktop.sh --network="$NETWORK"
fi

exec java -jar "$JAR" ${ARGS[@]+"${ARGS[@]}"}

#!/usr/bin/env bash
#
# Builds libsodium as static libraries for the iOS targets CoinSafeBox links
# (iosArm64 device, iosSimulatorArm64) and lays them out under
# composeApp/nativeLibs/libsodium/<arch>/{include,lib} for Kotlin/Native cinterop.
#
# Run on a macOS host with Xcode command-line tools installed.
# The built .a files are committed to the repo (version-pinned, supply-chain
# auditable via libsodium.sha256).
#
set -euo pipefail

SODIUM_VERSION=1.0.20
SODIUM_TARBALL="libsodium-${SODIUM_VERSION}.tar.gz"
# GitHub release mirror (download.libsodium.org is intermittently slow).
SODIUM_URL="https://github.com/jedisct1/libsodium/releases/download/${SODIUM_VERSION}-RELEASE/${SODIUM_TARBALL}"
IOS_MIN=14.0

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTDIR="$REPO_ROOT/composeApp/nativeLibs/libsodium"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

cd "$WORKDIR"

echo "==> Downloading $SODIUM_TARBALL"
curl -fsSL -o "$SODIUM_TARBALL" "$SODIUM_URL"

# Verify the downloaded tarball against the pinned checksum BEFORE extracting.
# This is mandatory: a missing checksum file is a fatal error, never a skip,
# so the build can never proceed with an unverified (potentially tampered)
# source tarball.
echo "==> Verifying tarball checksum"
if [[ ! -f "$REPO_ROOT/scripts/libsodium.sha256" ]]; then
    echo "FATAL: scripts/libsodium.sha256 not found — refusing to build from an unverified tarball" >&2
    exit 1
fi
echo "$(cat "$REPO_ROOT/scripts/libsodium.sha256")  $SODIUM_TARBALL" | shasum -a 256 -c -

tar xzf "$SODIUM_TARBALL"
cd "libsodium-stable"

build_slice() {
    local name="$1"
    local host="$2"
    local sdk="$3"
    local extra_cflags="$4"

    echo "==> Building $name ($host, sdk=$sdk)"
    mkdir -p "build-$name"
    (
        cd "build-$name"
        ../configure \
            --host="$host" \
            --disable-shared \
            --enable-static \
            --prefix="$(pwd)/out" \
            CFLAGS="-O2 -isysroot $(xcrun --sdk "$sdk" --show-sdk-path) -m${sdk}-version-min=$IOS_MIN $extra_cflags" \
            >configure.log 2>&1
        make -j"$(sysctl -n hw.ncpu)" >make.log 2>&1
        make install >install.log 2>&1
    )
}

# iOS device (arm64)
build_slice ios-arm64 arm64-apple-ios iphoneos ""

# iOS Simulator (arm64, Apple Silicon)
build_slice ios-sim-arm64 arm64-apple-ios-simulator iphonesimulator "-target arm64-apple-ios$IOS_MIN-simulator"

mkdir -p "$OUTDIR"
rm -rf "$OUTDIR/ios-arm64" "$OUTDIR/ios-sim-arm64"

for slice in ios-arm64 ios-sim-arm64; do
    dest="$OUTDIR/$slice"
    mkdir -p "$dest/include" "$dest/lib"
    cp -R "build-$slice/out/include/." "$dest/include/"
    # The raw Argon2 primitive header (argon2.h) is NOT installed by `make
    # install` — it lives in the source tree. Copy it so the cinterop wrapper
    # header (libsodium_wrapper.h) can include "crypto_pwhash/argon2/argon2.h".
    cp -R "src/libsodium/crypto_pwhash" "$dest/include/crypto_pwhash"
    cp "build-$slice/out/lib/libsodium.a" "$dest/lib/"
    echo "==> $slice -> $dest"
done

echo "==> Done. Layout:"
find "$OUTDIR" -maxdepth 3 -type d | sort

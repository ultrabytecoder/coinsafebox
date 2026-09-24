/*
 * cinterop wrapper header for libsodium.
 *
 * Binds the public API (sodium.h) plus the internal raw Argon2 primitive
 * (argon2.h), which sodium.h does NOT include. argon2id_hash_raw is what
 * CoinSafeBox uses for the PIN->KEK step because it accepts variable salt
 * lengths (the public crypto_pwhash API hardcodes a 16-byte salt).
 *
 * The relative include resolves against the -I paths cinterop is given
 * (the per-target include dir, which contains both sodium/ and the
 * crypto_pwhash/ tree from the libsodium build).
 */
#include "sodium.h"
#include "crypto_pwhash/argon2/argon2.h"

#!/usr/bin/env bash
# Build the musl loaders the APK packages, one per ABI.
#
# This is the loader's counterpart to scripts/gradle-build-native.sh: the only
# place that knows both the name stage-claude.sh writes and the name the APK
# packages. A program shipped as lib*.so, because the native library directory is
# executable whatever the app targets.
#
# Usage: build-loaders.sh [ABI ...]     defaults to both
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# The prefix compiled into the loader, which BootstrapRelocation.kt rewrites
# against whichever channel is running. Both sides have to name the same
# application id, and it has to be eleven characters: every path in this project
# is sized against that budget.
PREFIX_ID=apk.harness
PREFIX=/data/data/$PREFIX_ID/files/claude

PACKAGED=libmuslloader.so
ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64-v8a x86_64)

for abi in "${ABIS[@]}"; do
  case $abi in
    arm64-v8a) loader=ld-musl-aarch64.so.1 ;;
    x86_64)    loader=ld-musl-x86_64.so.1 ;;
    *) echo "no loader for $abi" >&2; exit 2 ;;
  esac

  out=$ROOT/build/loader/$abi
  mkdir -p "$out"
  "$ROOT/scripts/stage-claude.sh" \
    --loader-only --abi "$abi" --prefix "$PREFIX" --out "$out"

  dest=$ROOT/app/src/main/jniLibs/$abi
  mkdir -p "$dest"
  cp "$out/$loader" "$dest/$PACKAGED"
  chmod 644 "$dest/$PACKAGED"
  printf '== %s: %s (%s bytes)\n' "$abi" "$dest/$PACKAGED" "$(stat -c%s "$dest/$PACKAGED")"
done

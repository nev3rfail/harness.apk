#!/bin/sh
# Builds the mDNS-enabled adb from nmeum/android-tools git master.
# Usage: build-adb-mdns.sh [source-dir]   (default: ~/src/android-tools)
# Output: <source-dir>/build/vendor/adb, with RUNPATH $ORIGIN/../lib.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=${1:-$HOME/src/android-tools}

[ -d "$SRC/.git" ] || git clone --depth 1 https://github.com/nmeum/android-tools.git "$SRC"
cd "$SRC"

# Every submodule: the patch step runs over all vendored projects.
git submodule update --init --depth 1 --recommend-shallow
# The patch step uses `git am`, which needs an identity in each repository.
git config user.email build@localhost
git config user.name "harness build"
for d in $(awk '/path =/{print $3}' .gitmodules); do
    git -C "$d" config user.email build@localhost
    git -C "$d" config user.name "harness build"
done

cp "$HERE"/0029-*.patch patches/adb/
git apply -R --check "$HERE/cmake-sources.patch" 2>/dev/null ||
    git apply "$HERE/cmake-sources.patch"

# Termux's clang targets android24; the newer liblog symbols become weak refs.
FLAGS="-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__ -Wno-unguarded-availability -Wno-error=unguarded-availability"
cmake -B build -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_TOOLS_ADB_ENABLE_MDNS=ON \
    -DANDROID_TOOLS_USE_BUNDLED_LIBUSB=ON \
    -DOPENSSL_NO_ASM=ON \
    -DCMAKE_C_FLAGS="$FLAGS" \
    -DCMAKE_CXX_FLAGS="$FLAGS" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
ninja -C build adb

# Resolve libraries next to wherever the binary lands, on any channel.
patchelf --set-rpath '$ORIGIN/../lib' build/vendor/adb

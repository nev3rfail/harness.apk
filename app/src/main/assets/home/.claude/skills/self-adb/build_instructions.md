# Building an adb that can find this device by itself

Termux's `android-tools` adb is built **without** mDNS, so `adb mdns services`
answers `unknown host service 'mdns:services'`. Without it, getting back in
after a reboot means reading the Wireless-debugging port off the screen, because
that port is random and changes every toggle. With it, adb finds the device on
its own -- which is what lets the bootstrap in SKILL.md (discover over Wi-Fi,
then pin `tcpip 5555` for cellular) run without anyone touching the phone.

A prebuilt binary ships at `bin/adb-mdns` next to this file; copy that into
`$PREFIX/bin` rather than rebuilding. Build only if it is missing or the device
is a different architecture (this one is aarch64).

## Why the flag is all it takes

In `nmeum/android-tools` (the CMake repackaging of AOSP that Termux, Debian,
Alpine and Arch all ship) the whole feature hangs off one option:

```cmake
option(ANDROID_TOOLS_ADB_ENABLE_MDNS "Enable ADB mDNS support" OFF)   # CMakeLists.txt:15
```

Turning it ON compiles `adbmdns.cpp` + `adb_mdns.cpp`, defines `-DADB_MDNS=1`
and links `adbmdns_bridge`, a **Rust** crate built through corrosion. The repo's
`patches/adb/0002`, `0016` and `0017` look like they rip mDNS out, but every one
of them is an `#if ADB_MDNS` guard -- with the flag on they compile it **in**.
`0018`/`0019` are fixes to the Rust crate, so MDNS=ON is an intended
configuration, just not the one Termux ships.

Do **not** use the release tarball (e.g. `android-tools-35.0.2.tar.xz`): that
version has no such option, and its older Bonjour backend needs `<dns_sd.h>`,
which no Termux package provides. Use the git master tree.

## Recipe

```sh
apt install -y git cmake ninja rust corrosion protobuf googletest
git clone --depth 1 https://github.com/nmeum/android-tools.git ~/src/android-tools
cd ~/src/android-tools
git submodule update --init --depth 1 --recommend-shallow          # ALL of them, see below
git config user.email build@localhost && git config user.name "harness build"
for d in $(awk '/path =/{print $3}' .gitmodules); do
    git -C "$d" config user.email build@localhost
    git -C "$d" config user.name "harness build"
done
./build-adb-mdns.sh          # the script this build left in the tree
cp build/vendor/adb $PREFIX/bin/adb-mdns
```

`build-adb-mdns.sh` is just:

```sh
FLAGS="-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__ -Wno-unguarded-availability -Wno-error=unguarded-availability"
cmake -B build -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_TOOLS_ADB_ENABLE_MDNS=ON \
  -DANDROID_TOOLS_USE_BUNDLED_LIBUSB=ON \
  -DOPENSSL_NO_ASM=ON \
  -DCMAKE_C_FLAGS="$FLAGS" -DCMAKE_CXX_FLAGS="$FLAGS"
ninja -C build adb
```

## The seven things that go wrong

Every one of them comes from the same root assumption: this code expects Android
to be the **target**, never the **build host**. Here it is both.

| Symptom | Cause and fix |
|---|---|
| `Couldn't apply patches for adb` / `Committer identity unknown` | The build applies vendor patches with `git am`. Set `user.email`/`user.name` on the superproject **and every submodule** (locally -- do not touch global config) |
| `Couldn't apply patches for extras` | The patch step runs over *every* vendored project, not just adb's. Init **all** submodules, even ones adb does not need |
| `libprotobuf-lite.so ... does not exist` | Termux splits runtime and dev: `libprotobuf` is not enough, install `protobuf` (and `googletest`) |
| `'__android_log_logd_logger' is unavailable: introduced in Android 30` | Termux's clang targets `android24`; the device is API 35. Build with `-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__` and `-Wno-unguarded-availability` |
| `cannot find function monitor_network_changes_native` (E0425), then `cannot find value RTMGRP_LINK in crate libc` | Rust's `aarch64-linux-android` reports `target_os = "android"`, so the crate's linux-only `cfg` gates excluded the netlink backend, and `libc` omits `RTMGRP_LINK` there. Fixed by `patches/adb/0029-*`, which gates on `any(linux, android)` and defines the constant locally |
| `llvm-ar: p256-x86_64-asm-apple.S.o: No such file` | boringssl's asm selection mis-resolves this target and lists **x86_64 Apple** assembly. Build with `-DOPENSSL_NO_ASM=ON` (portable C; adb does not care) |
| `undefined symbol: LogdWrite()` / `incfs::SignalHandler::SignalHandler()` at the final link | Upstream lists sources for a **glibc host**, where `__ANDROID__` is undefined. Here it *is* defined, so those branches compile and need their implementations. Add `logging/liblog/logd_writer.cpp` to `liblog` (in `vendor/CMakeLists.adb.txt`) and `libziparchive/incfs_support/signal_handling.cpp` to `libzip` (in `vendor/CMakeLists.fastboot.txt`) |

A patch you write yourself must be **rebased onto the upstream series**, not
generated against the pristine checkout -- `0019` also edits `netwatch`, so a
patch cut from pristine fails to apply when it runs last. Apply
`patches/adb/00*.patch` first, make the change, `git format-patch -1 --start-number 29`,
then reset the submodule so CMake applies the whole series itself. Keeping it as
a numbered patch (rather than a loose edit) also survives the `git submodule
update` that the patch step runs on every configure.

## Known defect in this build

**`adb-mdns pair` segfaults the server.** Pairing with it crashes in the pairing
path; stock `adb pair` works fine and does the same job. Pairing is one-time,
so: pair with stock `adb`, then use `adb-mdns` for everything afterwards. Do not
spend time on this unless the pairing has to be redone often.

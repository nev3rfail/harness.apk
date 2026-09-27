---
name: harness-device-build
description: Use when building or installing this app's APK on the device itself -- Gradle, AGP, aapt2, the Android SDK, the NDK, zig, the renderer or the musl loader, signing, or replacing an installed channel from inside the app sandbox.
---

# Building the APK on the device

Everything the build needs is packaged for Android by Termux or hand-installed
once. Google's own tooling is x86_64-only and never runs here, so the build is
Termux's binaries, one hand-placed platform jar, and the native libraries of the
app that is already running. The install goes through the device's own `adb`.

## Quick reference

| Piece | Where it comes from |
|---|---|
| JDK | `pkg install openjdk-21` |
| `aapt2`, `d8`, `apksigner` | `pkg install aapt2 d8 apksigner` -- Termux builds these for arm64 |
| `adb` | `adb-mdns`, which ships with `self-adb`; `pkg install android-tools` for the stock one |
| Gradle | the repository's `./gradlew`, which pins 8.11.1 and pairs with AGP 8.9.1 |
| Platform jar | by hand from `dl.google.com`, below |
| Native libraries | the running app's own, through `-PprebuiltNative`, below |

The packaged `gradle` is 9.6.0 and does not pair with this AGP. Use the wrapper.

## The hand-placed platform jar

No package carries one. Take `platform-36_r02.zip` from
`https://dl.google.com/android/repository/`, unzip it, and rename the extracted
directory to `android-36` under an SDK root you own:

```
$HOME/android-sdk/platforms/android-36/android.jar
```

`local.properties` names that root, and the shell writes that line too:

```sh
printf 'sdk.dir=%s/android-sdk\n' "$HOME" > $HOME/harness.apk/local.properties
```

That file is git-ignored, which is where a device-specific path belongs.

## The override that makes it work

AGP fetches its own `aapt2` from Maven as a linux-x86_64 binary, which cannot
execute here. Point it at Termux's build instead, in
`$HOME/.gradle/gradle.properties` -- never in the repository's, which is
committed and would break every other machine.

Every absolute path here is written by the shell, because a typed one is wrong:
the prefix is `root/usr` inside a channel's data directory, and both halves of
that differ per channel.

```sh
mkdir -p $HOME/.gradle
cat > $HOME/.gradle/gradle.properties <<EOF
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
EOF
cat $HOME/.gradle/gradle.properties      # read back what it actually says
```

`gradle.properties` does not expand variables, which is why the heredoc is
unquoted and the shell does the expanding.

## Build

```sh
cd $HOME/harness.apk
./gradlew assembleDebug --no-daemon \
  -PprebuiltNative="$(dirname "$HARNESS_TERMUX_EXEC")" \
  -PprebuiltAbi="$(getprop ro.product.cpu.abi)"
```

A debug build takes the `dev.harness` id unless `-PharnessAppId` says otherwise,
so the default target is the channel that is safe to break.

`-PprebuiltNative=<dir>` skips every CMake target, the terminal library's
included, and the loader and proot builds, and packages every `lib*.so` in `<dir>` as the
ABI `-PprebuiltAbi` names (`arm64-v8a` when it is left out). `$HARNESS_TERMUX_EXEC`
is the exec router in the running app's native library directory, so that
directory holds the renderer, the loader, the router, proot and the rest, already
built for this device. Its path changes on every update: take it from the variable,
never type it.

**Build against the channel that is not hosting your session.** A Gradle JVM at
`-Xmx1536m` in the same sandbox as the agent is enough for Android to reclaim the
app, and the session dies with it.

## The renderer and the loader, built here

Both builds need static programs, which the exec router runs under proot (the
harness-userland skill has how):

- **zig 0.16.0**, the version `android/renderer/build.zig.zon` asks for, as a
  static Linux build. `termux.net` carries no `zig`.
- **The termux-ndk NDK**, `lzhiyong/termux-ndk`'s arm64 build of NDK r29, whose
  `clang-21` and `lld` are static. Unpacked to
  `$HOME/android-sdk/ndk/29.0.14206865`, it reports the revision this project
  pins.
- `pkg install patchelf xz-utils`. The bootstrap has no `xz`, which `tar`
  needs for both archives below.

Both downloads are pinned by sha256; a different hash means a different file, so
stop there:

```sh
cd $HOME
curl -fL --max-time 600 -o zig.tar.xz \
  https://ziglang.org/download/0.16.0/zig-aarch64-linux-0.16.0.tar.xz
echo "ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17  zig.tar.xz" | sha256sum -c -
tar -xf zig.tar.xz && rm zig.tar.xz      # $HOME/zig-aarch64-linux-0.16.0/zig
curl -fL --max-time 1800 -o ndk.tar.xz \
  https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.tar.xz
echo "02e10e4ddfe8deaeb0bd0cf29d04c981ed5bc8a5d6b560ebb9e7661f472d684b  ndk.tar.xz" | sha256sum -c -
mkdir -p $HOME/android-sdk/ndk
tar -xf ndk.tar.xz -C $HOME/android-sdk/ndk && rm ndk.tar.xz
mv $HOME/android-sdk/ndk/android-ndk-r29 $HOME/android-sdk/ndk/29.0.14206865
```

The NDK archive is 361 MB and unpacks to several GB; ask the operator first.

**The renderer,** under one proot for the whole build:

```sh
cd $HOME/harness.apk/vendor/ghostty-android
proot --exec-bin "$PREFIX/bin" \
  --bind="$HARNESS_ETC/resolv.conf:/etc/resolv.conf" \
  --bind="$HARNESS_ETC/hosts:/etc/hosts" \
  --bind="$PREFIX/etc/tls/cert.pem:/etc/ssl/certs/ca-certificates.crt" \
  env ZIG=$HOME/zig-aarch64-linux-0.16.0/zig \
      ANDROID_NDK_ROOT=$HOME/android-sdk/ndk/29.0.14206865 \
      ZIG_CACHE_ROOT=$HOME/.cache/ghostty-android \
  bash scripts/build-android-nonix.sh arm64-v8a
```

A `proot` typed by name binds nothing, so the build names the bindings the router
gives a static program: `resolv.conf`, `hosts` and the certificates, which let
zig fetch its dependencies itself. The script writes a libc file naming the NDK's sysroot
and targets `aarch64-linux-android.24`, which is what `zig cc` for Android needs.
`ZIG_CACHE_ROOT` must be set: its default is `/tmp`. It holds the fetched
dependencies, so keep it. A cold build takes about 19 minutes, and the two
libraries land in `android/terminal-library/src/main/jniLibs/arm64-v8a/`.

**The loader,** under one proot for the whole build:

```sh
cd $HOME/harness.apk
NDK=$HOME/android-sdk/ndk/29.0.14206865
out=$PWD/build/loader/arm64-v8a
proot --exec-bin "$PREFIX/bin" env \
  CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --target=aarch64-linux-android21" \
  bash scripts/stage-claude.sh --loader-only --abi arm64-v8a --out "$out"
cp "$out"/ld-musl-aarch64.so.1 app/src/main/jniLibs/arm64-v8a/libmuslloader.so
```

About a minute, musl's own download included; with no outer proot, each compile
starts one of its own and the build takes about half as long again. The download
is bionic `curl`, which resolves names without `/etc`, so this proot needs no
bindings.
`--exec-bin` points `#!/bin/sh` in musl's `configure` at the userland's shell.
The termux-ndk keeps Google's directory name `linux-x86_64` for its arm64
toolchain. The script's `verify_loader` gate is what says it worked. Use the NDK's clang,
not `zig cc`, which is itself a musl toolchain and shadows musl's own `memcpy`.

**Packaging them.** `-PprebuiltNative` takes every native library from one
directory, so give it the running app's libraries with the rebuilt ones on top:

```sh
libs=$TMPDIR/native; rm -rf "$libs"; mkdir -p "$libs"
cp "$(dirname "$HARNESS_TERMUX_EXEC")"/lib*.so "$libs"/
cp vendor/ghostty-android/android/terminal-library/src/main/jniLibs/arm64-v8a/lib*.so \
   app/src/main/jniLibs/arm64-v8a/libmuslloader.so "$libs"/
./gradlew assembleDebug --no-daemon -PprebuiltNative="$libs" -PprebuiltAbi=arm64-v8a
```

The CMake targets -- the router, `librelocate` -- and proot are built on a
desktop.

## Install

`pm install` cannot work from in here: `system_server` is denied read access to
both the app's own directory and `/sdcard`, and `/data/local/tmp` is not
writable. The path is the device's own `adb`, which `self-adb` covers -- getting
a transport at all is that skill's job, because `127.0.0.1:5555` answers only
once `tcpip 5555` has been pinned and the property resets on every reboot. With
one connected:

```sh
adb-mdns -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Several transports can be connected at once, so always pass `-s`. This also
unlocks `logcat`, `dumpsys` and `am start`, which the app's own uid may not run.
Keep `logcat` bounded with `--pid`, a tag, or a small `-t`; a broad dump outruns
a two-minute timeout.

**An instrumented build can look inert.** Where `getprop log.tag` reads `I`,
every `Log.d` is dropped before it reaches `logcat`, so a build full of debug
logging looks as though its code never ran. One tag at a time is opened with
`adb -s 127.0.0.1:5555 shell setprop log.tag.<TAG> VERBOSE`, until the next
reboot.

## Common mistakes

| Mistake | What is true |
|---|---|
| Fetching Google's NDK to build native code | It is x86_64. The termux-ndk NDK is arm64 and runs under proot |
| Running the renderer build under a bare `proot` | It binds no `resolv.conf`, so zig cannot fetch. Pass the three `--bind`s above |
| Typing the native library directory | It changes on every update. `dirname "$HARNESS_TERMUX_EXEC"` |
| Hunting third-party `aapt2` prebuilts | Termux packages the arm64 build; install it and override the Maven one |
| Unzipping Google's `build-tools_r*-linux.zip` | Those binaries are x86_64 and will not exec |
| Assembling a full SDK by hand | Only the platform jar is needed; the override covers the rest |
| Putting the override in the repository's `gradle.properties` | It names this installation's prefix; it belongs in `$HOME/.gradle` |
| Uninstalling to clear a signature mismatch | The channel's data directory holds the agent, its credentials and every transcript. Copy the installed `base.apk` aside (`pm path <pkg>`) and sign to match instead |

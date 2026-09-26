---
name: harness-device-build
description: Use when building or installing this app's APK on the device itself -- Gradle, AGP, aapt2, the Android SDK, the NDK, signing, or replacing an installed channel from inside the app sandbox.
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
included, and the loader build, and packages every `lib*.so` in `<dir>` as the
ABI `-PprebuiltAbi` names (`arm64-v8a` when it is left out). `$HARNESS_TERMUX_EXEC`
is the exec router in the running app's native library directory, so that
directory holds the renderer, the loader, the router and the rest, already built
for this device. Its path changes on every update: take it from the variable,
never type it.

**Build against the channel that is not hosting your session.** A Gradle JVM at
`-Xmx1536m` in the same sandbox as the agent is enough for Android to reclaim the
app, and the session dies with it.

## What cannot be built here

The renderer, the musl loader and the CMake targets cannot be built on the
device, so only a Kotlin or resource change builds here. Android runs no
statically linked program from the app's data directory, and each of those builds
needs one: Termux's `zig` package and the `termux-ndk` toolchain are static, and
`termux.net` carries no `zig`. A change to native code is built on a desktop.

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
| Fetching the NDK or `zig` to build native code | Both are static and cannot run here. Build with `-PprebuiltNative` |
| Typing the native library directory | It changes on every update. `dirname "$HARNESS_TERMUX_EXEC"` |
| Hunting third-party `aapt2` prebuilts | Termux packages the arm64 build; install it and override the Maven one |
| Unzipping Google's `build-tools_r*-linux.zip` | Those binaries are x86_64 and will not exec |
| Assembling a full SDK by hand | Only the platform jar is needed; the override covers the rest |
| Putting the override in the repository's `gradle.properties` | It names this installation's prefix; it belongs in `$HOME/.gradle` |
| Uninstalling to clear a signature mismatch | The channel's data directory holds the agent, its credentials and every transcript. Copy the installed `base.apk` aside (`pm path <pkg>`) and sign to match instead |

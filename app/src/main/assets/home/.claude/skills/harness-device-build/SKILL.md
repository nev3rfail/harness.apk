---
name: harness-device-build
description: Use when building or installing this app's APK on the device itself -- Gradle, AGP, aapt2, the Android SDK, the NDK, signing, or replacing an installed channel from inside the app sandbox.
---

# Building the APK on the device

Everything the build needs is packaged for Android by Termux or hand-installed
once. Google's own tooling is x86_64-only and never runs here, so the build is
Termux's binaries plus two hand-placed pieces, and the install goes through the
device's own `adb`.

## Quick reference

| Piece | Where it comes from |
|---|---|
| JDK | `pkg install openjdk-21` (17 is packaged too; AGP takes either) |
| `aapt2`, `d8`, `apksigner` | `pkg install aapt2 d8 apksigner` -- Termux builds these for arm64 |
| `adb` | `pkg install android-tools` |
| `clang`, `cmake`, `ninja` | `pkg install clang cmake ninja` |
| Gradle | the repository's `./gradlew`, which pins 8.11 and pairs with AGP 8.7.3 |
| Platform jar | by hand from `dl.google.com`, below |
| NDK | by hand, below, or avoided with `-PskipNativeBuild=true` |

The packaged `gradle` is 9.7.1 and does not pair with this AGP. Use the wrapper.

## The two hand-placed pieces

**The platform jar.** No package carries one. Take `platform-35_r02.zip` from
`https://dl.google.com/android/repository/`, unzip it, and rename the extracted
directory to `android-35` under an SDK root you own:

```
$HOME/android-sdk/platforms/android-35/android.jar
```

`local.properties` names that root, and the shell writes that line too:

```sh
printf 'sdk.dir=%s/android-sdk\n' "$HOME" > $HOME/harness.apk/local.properties
```

That file is git-ignored, which is where a device-specific path belongs.

**The NDK, only if CMake has to run.** Google publishes no arm64-Linux host
tooling at all, so their NDK cannot be used. `lzhiyong/termux-ndk` publishes
`android-ndk-r29-aarch64.tar.xz`, built with Zig and musl, reporting exactly the
`Pkg.Revision` this project pins. Unpacked to
`$ANDROID_HOME/ndk/<pinned-revision>/`, AGP finds it with no other change. It is
1.8 GB for two small libraries, so reach for it only when you need them.

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
./gradlew assembleDebug --no-daemon -PskipNativeBuild=true -PskipLoaderBuild
```

A debug build takes the `dev.harness` id unless `-PharnessAppId` says otherwise,
so the default target is the channel that is safe to break.

The two flags consume the native pieces already in `jniLibs` rather than
building them: the renderer's `libghostty-vt.so` and `libghostty_renderer.so`
under `terminal-library/src/main/jniLibs/<abi>/`, and `libmuslloader.so` under
`app/src/main/jniLibs/<abi>/`. Neither is committed, so an APK built with the
flags and nothing in place installs and then throws in
`AgentStage.stageLoader`.

**Build against the channel that is not hosting your session.** A Gradle JVM at
`-Xmx1536m` in the same sandbox as the agent is enough for Android to reclaim the
app, and the session dies with it.

## The native pieces, built here

**The renderer** is Zig, and the version is not negotiable: both `build.zig.zon`
files pin a `minimum_zig_version` that Termux's `zig` package satisfies, so
`$PREFIX/bin/zig` is the one to use and a tarball fetched by hand is refused.
`patchelf` comes from Termux too.

Zig resolves the whole dependency manifest before it compiles anything, and its
static binary reads `/etc/resolv.conf`, which Android has not got, so every fetch
ends in `TemporaryNameServerFailure`. `curl` resolves names normally, so the loop
is: run the build, take the URL out of the error, `curl` it, hand the file to
`zig fetch`, run again. The directory those land in is the one never to delete --
a dependency bump shows up here as a build that stops on one URL.

**The loader** is built by `scripts/stage-claude.sh`. Its wrapper
`scripts/build-loaders.sh` cannot run it for you: both are committed
non-executable, so the wrapper fails on the script it calls. Drive the inner one
with the NDK's clang, and not with `zig cc`, which is itself a musl toolchain and
shadows musl's own `memcpy`:

```sh
NDK=$HOME/android-sdk/ndk/29.0.14206865
out=$PWD/build/loader/arm64-v8a
CC=$NDK/toolchains/llvm/prebuilt/linux-aarch64/bin/aarch64-linux-android21-clang \
  bash scripts/stage-claude.sh --loader-only --abi arm64-v8a \
  --prefix $HOME/claude --out "$out"
cp "$out"/ld-musl-aarch64.so.1 app/src/main/jniLibs/arm64-v8a/libmuslloader.so
```

About a minute, musl's own download included, and the script's `verify_loader`
gate is what says it worked. `x86_64` goes the same way through
`x86_64-linux-android21-clang`. The prefix compiled in is rewritten per channel
at install time, so it names whichever channel you build from.

## Install

`pm install` cannot work from in here: `system_server` is denied read access to
both the app's own directory and `/sdcard`, and `/data/local/tmp` is not
writable. The path is the device's own `adb`:

```sh
adb connect 127.0.0.1:5555          # accept the debugging dialog once
adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Two transports appear for one device -- `127.0.0.1:5555` and an auto-detected
`emulator-5554` -- so always pass `-s`. This also unlocks `logcat`, `dumpsys` and
`am start`, which the app's own uid may not run. Keep `logcat` bounded with
`--pid`, a tag, or a small `-t`; a broad dump outruns a two-minute timeout.

**An instrumented build can look inert.** Where `getprop log.tag` reads `I`,
every `Log.d` is dropped before it reaches `logcat`, so a build full of debug
logging looks as though its code never ran. One tag at a time is opened with
`adb -s 127.0.0.1:5555 shell setprop log.tag.<TAG> VERBOSE`, until the next
reboot.

## Common mistakes

| Mistake | What is true |
|---|---|
| Hunting third-party `aapt2` prebuilts | Termux packages the arm64 build; install it and override the Maven one |
| Unzipping Google's `build-tools_r*-linux.zip` | Those binaries are x86_64 and will not exec |
| Assembling a full SDK by hand | Only the platform jar is needed; the override covers the rest |
| Putting the override in the repository's `gradle.properties` | It names this installation's prefix; it belongs in `$HOME/.gradle` |
| Uninstalling to clear a signature mismatch | The channel's data directory holds the agent, its credentials and every transcript. Copy the installed `base.apk` aside (`pm path <pkg>`) and sign to match instead |

# Building proot

`proot-source.tar.gz`, a release asset, holds the corresponding source of
`libproot.so`, `libproot-talloc.so` and `libproot-loader.so` as the APK of that
release carries them. Its paths are the repository's, and the pinned downloads
are already under `build/proot/dl`, so it builds offline.

The same release carries `app-play.aab`, the unsigned `play` bundle built at the
release's tag. A Play upload is that bundle, signed with the upload key, and its
`assets/notices/proot/SOURCE` names this release's `proot-source.tar.gz`.

## Requirements

A Linux x86_64 host with `bash`, `curl`, `make`, `patch`, `unzip` and binutils'
`readelf`, and the Android NDK whose revision
`build/proot/NDK_REVISION` names. `build-proot.sh` uses `$ANDROID_NDK_ROOT`, else
`$ANDROID_HOME/ndk/<revision>`, else downloads that NDK and checks its sha256.

## Build

    tar -xzf proot-source.tar.gz
    bash scripts/build-proot.sh               # arm64-v8a and x86_64
    bash scripts/build-proot.sh arm64-v8a     # one ABI

The three files of each ABI land in `build/proot/<abi>/` and in
`app/src/main/jniLibs/<abi>/`.

## talloc alone

`libproot-talloc.so` is `talloc.c` compiled with the committed
`app/src/main/cpp/proot/talloc/config.h`. For arm64-v8a, from the source's root
(for x86_64 the compiler is `x86_64-linux-android30-clang`):

    mkdir -p build/talloc
    tar -xzf build/proot/dl/talloc-2.4.3.tar.gz -C build/talloc --strip-components=1
    $ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang \
        -O2 -fPIC -fvisibility=hidden -shared \
        -Iapp/src/main/cpp/proot/talloc -Ibuild/talloc/lib/replace -Ibuild/talloc \
        build/talloc/talloc.c -o libproot-talloc.so \
        -Wl,-soname,libproot-talloc.so -Wl,-z,max-page-size=16384 -Wl,--no-undefined
    $ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
        --strip-unneeded libproot-talloc.so

talloc's messages carry talloc.c's path, so the file's bytes and addresses
depend on the directory it was built in; its exported symbols, soname and
DT_NEEDED do not.

## Installing a rebuilt libproot-talloc.so, or any of the three

The files live in the APK as `lib/<abi>/<name>` and are installed into the app's
native library directory. An APK can only be changed by re-signing it, and
Android updates an installed app only with an APK signed by the same key, so a
modified APK is signed with a key of your own and replaces the installed app:

    cp harness.apk modified.apk
    mkdir -p lib/arm64-v8a
    cp build/proot/arm64-v8a/libproot-talloc.so lib/arm64-v8a/
    zip modified.apk lib/arm64-v8a/libproot-talloc.so
    zipalign -p -f 4 modified.apk aligned.apk
    keytool -genkeypair -keystore my.p12 -storetype PKCS12 -alias me \
        -keyalg RSA -keysize 4096 -validity 10000
    apksigner sign --ks my.p12 --ks-key-alias me aligned.apk
    adb uninstall <application id>        # the installed app has another signer
    adb install aligned.apk

`zipalign` and `apksigner` are in the Android SDK's build tools. Uninstalling
removes the app's data. `libproot.so` finds `libproot-talloc.so` beside itself
by soname, so a replacement needs only the same soname, `libproot-talloc.so`.

## PROOT_TMP_DIR

`--sysvipc` publishes a keyed shared-memory segment in `$PROOT_TMP_DIR`. A proot
started without it answers a keyed `shmget` with `ENOENT`.

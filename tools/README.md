# Diagnostics

Small programs for measuring what an Android app process is actually allowed to
do. They answer questions that are otherwise guesswork, and the answers change
with the Android version, the architecture and the app's `targetSdkVersion`.

Build them for the device, not the host:

```sh
NDK=$ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/<host>/bin
$NDK/x86_64-linux-android28-clang -O1 -o seccomp-scan seccomp-scan.c
```

Run them **from inside the app**, not through `adb shell run-as`. `run-as` puts
the process in the `runas_app` SELinux domain with a different seccomp filter,
so it reports on a sandbox the app does not live in. Push the binary into the
app's files directory and launch it from the app's own terminal.

## seccomp-scan

Prints every syscall number the policy answers with SIGSYS, by running each one
in its own forked child. `blocked-syscalls-x86_64.txt` is what it reports on
Android 15 / x86_64 / `targetSdk 28`: 238 of 463, essentially the whole set of
legacy calls that bionic never makes and Android therefore never allowlisted.

That result is why `syscall-shim` exists.

## netcheck

Prints what the app can reach: the platform resolver, a direct connection to
what it resolves, a UDP query straight at a public resolver, and a plain TCP
connection. It separates "the app has no network" from "the program's own
resolver has no nameserver to read", which look identical from inside a runtime
that carries its own DNS.

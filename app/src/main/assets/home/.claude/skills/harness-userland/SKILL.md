---
name: harness-userland
description: Use when installing packages, running scripts, or looking for headers, libraries or binaries on this device -- anything where a path from documentation or memory might be wrong, an `apt`/`pkg` install is involved, or a `#!/usr/bin/env` shebang fails.
---

# The userland this device runs

The shell is bash from a Termux userland relocated into an app's private
directory. Ordinary tools behave normally. What is different is where everything
lives and what may be written.

## The prefix

Read it, do not type it:

```sh
echo $PREFIX          # root/usr inside this channel's data directory
```

Nothing is at `/usr`, `/bin` or `/etc`, and `/` is not writable. Headers are at
`$PREFIX/include`, libraries at `$PREFIX/lib`, binaries at `$PREFIX/bin`. A path
copied out of documentation is wrong here unless it starts with `$PREFIX` or the
app's own directory.

Writable: this channel's data directory, and `$TMPDIR` for scratch. Everything
else, including `/tmp`, is not.

## Installing

`apt install <package>` and `pkg install <package>` both work and reach Termux's
repository, which carries a full toolchain -- compilers, JDKs, Android build
tools, `git`, `jq`, `python`.

Every archive is rewritten on the way in, because a Termux `.deb` unpacks to an
absolute path this app cannot write and a shim standing in for `dpkg` re-roots
it. So an install costs roughly twice the usual time and prints the shim's
output. Install what you need; do not install what you merely might need --
storage here is shared with the operator's photos.

## `#!/usr/bin/env` does not resolve

The kernel reads a shebang literally, and there is no `/usr/bin/env`:

```
bash: ./script.sh: /usr/bin/env: bad interpreter: No such file or directory
```

Three ways through it, in order of preference:

```sh
bash script.sh                        # name the interpreter yourself
python3 script.py
sed -i "1s|.*|#!$PREFIX/bin/env python3|" script.sh    # rewrite the shebang
```

`termux-exec` is what rewrites these shebangs when it is preloaded, and the app
preloads it into the shell it hands the operator. It is not in front of the
shells your own tools run, which is why the failure above is what you will see.
It also cannot be preloaded into the agent: it is built against bionic and the
agent is musl, so the relocation fails. Name the interpreter instead of trying to
force the library in.

## Quick reference

| Question | Answer |
|---|---|
| Where is a header? | `$PREFIX/include` |
| Where do packages install? | under `$PREFIX`, never `/usr` |
| Can I write outside the app directory? | No. `$TMPDIR` for scratch |
| Is there a package index? | Yes, Termux's, live and complete |
| Why is this install slow? | every `.deb` is re-rooted on the way in |
| `/usr/bin/env` shebang fails? | expected; name the interpreter |
| `sudo`, a service manager, a port below 1024? | none of them exist |

## Common mistakes

| Mistake | What is true |
|---|---|
| Assuming `$PREFIX` is `files/usr` or `/usr` | It is `root/usr` inside the channel's data directory. Ask the shell |
| Concluding packages cannot be installed | `apt` works; the repository is complete |
| Preloading `termux-exec` by hand to fix a shebang | It cannot relocate against the agent's libc. Name the interpreter |
| Writing scratch files to `/tmp` | Not writable. Use `$TMPDIR` |
| Installing a whole language runtime for a one-line job | `awk`, `sed` and `jq` are already here or one small install away |

# Projects

The agent runs in one directory and that directory is fixed at spawn. Today it is
`filesDir`, hardcoded in three places that each want it for a different reason.
This is what it takes to let a person pick the directory instead, on shared
storage or app-private, and have everything follow.

## What moves and what does not

Four things currently read `filesDir`, and only one of them is the workspace:

| Role | Where it lives | Moves with the project |
| --- | --- | --- |
| `HOME` -- config, credentials, staged binary | `filesDir` | no |
| lock directory -- `$HOME/.claude/ide` | `filesDir/.claude/ide` | no |
| MCP server config | `filesDir` | no |
| working directory -- the project | picked | **yes** |

`HOME` stays because everything under it is per-installation, not per-project:
the credentials copied in by hand, the onboarding flag, the staged agent whose
musl loader has its prefix compiled in. A moving `HOME` would mean copying all of
that per project for nothing.

Only `cwd` and the `workspaceFolders` entry in the lockfile change. That is
enough: the CLI considers a lock only when one of its `workspaceFolders` is the
agent's cwd or an ancestor of it, and it reads project configuration relative to
cwd.

## Component breakdown

**`Projects`** (new, one file). The state and the derivation. Holds the current
project and the recent list in `SharedPreferences`, derives a filesystem path
from a tree URI, and probes a path before anyone is allowed to depend on it.
Knows nothing about the agent.

**`Agent`** (existing). Takes the workspace as a parameter instead of reading
`filesDir` for it, and passes the MCP config path on the command line. Everything
else it assembles -- resolver, preload, shim -- is per-installation and unchanged.

**`IdeServer`** (existing). Already takes `workspace` and `lockDirectory`
separately, which is exactly the split needed. Gains a way to await the lockfile,
because the port is bound and the file written asynchronously, and a respawn no
longer has the incidental delay that hides it.

**`PanelServer`** (existing). Writes its config to a path the caller names rather
than to `workspace/.mcp.json`.

**Session holder** (new, in `MainActivity` to start with). Owns the four things
that must change together -- agent process, IDE server, panel server, terminal
geometry -- and exposes one operation: open this project. Startup is that
operation with nothing to tear down.

**Project picker** (new UI). A dialog, for the same reason every other panel is
one: the terminal renders on a surface composited above the app's own window, so
nothing drawn in that window can cover it.

## Deriving a path from a tree URI

`ACTION_OPEN_DOCUMENT_TREE` returns `content://<authority>/tree/<documentId>`.
One authority describes a real filesystem mount:

`com.android.externalstorage.documents` -- the document id is
`<volume>:<relative path>`. `primary` is `Environment.getExternalStorageDirectory()`;
any other volume is `/storage/<volume>`, which is where the platform mounts
removable media. The relative part is appended.

Every other authority is refused with a plain message.
`com.android.providers.downloads.documents` and
`com.android.providers.media.documents` hand out opaque ids over a media
database, and a cloud provider has no local path at all. A `raw:` document id
carries an absolute path and could be accepted in one line; it is not worth
carrying until something produces one.

The derivation is a string rule on purpose. Enumerating storage volumes properly
means `StorageManager` and, below API 30, reflection to get a volume's path. The
probe below decides whether the guess was right, which makes the reflection
unnecessary.

### Verifying, not trusting

The document id is text from another app's provider, and the mount it names may
be read-only, absent, or unwritable by this uid. A derived path is accepted only
after all of:

1. **Canonicalise.** `File(path).canonicalFile` resolves `..` and the symlinks
   the platform keeps (`/sdcard`, `/storage/self/primary`).
2. **Contain.** The canonical path must equal the canonical volume root or start
   with it plus a separator. A document id containing `..` then cannot escape the
   volume it named.
3. **Exist as a directory.**
4. **Round-trip a byte.** Create a dot file in it, write, read back, delete. Not
   `canWrite()`: an emulated volume can report a permission it does not honour,
   and the agent's first act in a workspace is to write to it.

Failure at any step leaves the current project untouched and says which step
failed. There is no partial open.

A secondary volume is the case that fails step 4 in normal operation. Legacy
external storage grants broad write only on the primary volume; on removable
media an app may write only its own `Android/data/<package>` directory. The
honest fallback offered there is a project under `getExternalFilesDirs(null)` for
that volume -- writable, visible over MTP, and needing no permission at all.

### App-private projects

The system picker cannot reach `filesDir`; SAF has no provider for it. So
app-private projects are not browsed to, they are named: "New project" creates
`filesDir/projects/<name>`. This is not a second folder chooser, it is a list of
directories the app itself made.

`filesDir` remains the default project, so an installation that never picks
anything behaves as it does now and keeps the trust answer it already has.

## Permissions

`READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` in the manifest, with no
`maxSdkVersion`. Both are needed on every version the app runs on.

`targetSdk` is 28, so the app gets the legacy storage view: the shared volume is
one filesystem the app's uid can read and write, not a per-app sandbox.
`MANAGE_EXTERNAL_STORAGE` is not needed and should not be requested -- it is a
strictly larger grant for a capability legacy storage already provides.

**When to ask.** After the folder is picked and derived, before the probe, and
only if the derived path is outside the app's own directories. An app-private
project asks for nothing. Asking at launch would ask for a permission most
sessions never need.

**When refused.** The project is not opened and the current one keeps running.
The two remaining options are stated: grant the permission, or make the project
app-private. Nothing is copied into app-private storage on the user's behalf --
that is a synchronisation problem, and the agent would be editing a copy.

**Confirming the agent can read it.** The agent is not a separate app; it is a
`fork`/`exec` of the app process. It runs with the same uid, in the same mount
namespace -- inherited across fork, which is why the app's storage view is the
agent's storage view -- and in the same SELinux domain: at `targetSdk` 28 the
policy grants `execute_no_trans` on the app's data directory, which is permission
to execute *without* a domain transition. So a byte the app just round-tripped
through the directory is a byte the agent can round-trip too. The probe is the
confirmation.

The one thing that does not survive is a process forked before a grant. Never
reuse an agent that predates a permission change; respawn. The switch does that
anyway.

**What external storage does not have.** No POSIX modes, no symlinks, no
executable bit. A project on the shared volume cannot hold a git checkout with
mode bits or links intact. App-private projects have a real filesystem underneath
and do not have this problem.

## The respawn

Nothing is torn down until the target has passed derivation, permission and
probe. A failed switch changes nothing.

1. **Stop accepting.** Mark the switch in progress so no new surface opens.
2. **Resolve the pending diff.** `Surfaces.dismiss()` completes an unanswered
   diff as rejected, which is already the right answer: the tool call waiting on
   it belongs to a process about to be killed, and something has to be returned
   so the coroutine awaiting it ends.
3. **Kill the agent.** `session.stop()` hangs up the pty and signals the process
   group. Poll `isRunning` until it is false. The agent goes first so it never
   observes a half-removed configuration, and so it cannot rewrite `.claude.json`
   under step 6.
4. **Stop the panel server.** Deletes the config file it wrote and closes its
   socket.
5. **Stop the IDE server.** Deletes the lockfile and closes the socket. Both
   servers are discarded rather than restarted; each cancels the scope it owns.
6. **Seed the agent's per-project state**, while nothing is running to fight over
   it. Merge `projects["<path>"].hasTrustDialogAccepted = true` into
   `filesDir/.claude.json`, and ensure `enabledMcpjsonServers` names the panel
   server in `filesDir/.claude/settings.json`. Without these, every new project
   costs two dialogs answered on the phone -- the CLI names both keys in its own
   error messages. Merge with `org.json`; the agent owns this file and the app
   only adds to it.
7. **Record the project** as current, and at the front of recents.
8. **Start a panel server**, writing its config into `filesDir`.
9. **Start an IDE server** for the new workspace and *wait for its lockfile*. Its
   start already deletes every `*.lock` in the directory, which is the same
   cleanup that covers a lock left behind by a process that was killed.
10. **Clear the terminal.** `ESC[?1049l ESC[H ESC[2J ESC[3J` through the
    renderer, so the dead agent's last screen is not the backdrop for the new
    one. The renderer exposes no reset of its own.
11. **Spawn.** A new session with `cwd` at the project, started at the geometry
    last reported by the surface -- there will be no new `onSurfaceReady`.

Startup is steps 6 through 11 against the stored project, or against `filesDir`
if the stored one no longer probes clean.

### Where the auth token goes

The panel server's config carries a bearer token for a loopback server that can
draw panels and read files. Written into a project on the shared volume it is
readable by every other app holding storage permission. So it is not written
there: the config stays in `filesDir` and is named on the command line with
`--mcp-config <path>`, which takes a JSON file. `.mcp.json` in the workspace
stops being written at all.

This is a regression the project feature would otherwise introduce, so it belongs
in the same slice rather than after it.

## Project state

`SharedPreferences`, one file, two keys:

- `current` -- absolute path. Absent means `filesDir`.
- `recent` -- a JSON array of absolute paths, most recent first, capped at eight.

Nothing else. Not the tree URI: the path is what the agent needs and what
everything keys on, and the URI is only useful to SAF, which nothing here reads
through. The SAF grant is therefore not persisted either -- one line to add when
something actually opens a document provider.

Recents are validated when chosen, not swept at startup. An unmounted card would
otherwise erase the list.

## First slice

- Project state and derivation, with the probe.
- A header line above the terminal naming the current project; tapping it opens
  the picker. It is the only place the current workspace is visible at all, which
  is reason enough for the row it costs.
- Picker: recents, "New project" under `filesDir/projects`, "Open folder" into
  the system picker.
- The respawn sequence, including the lockfile wait and the terminal clear.
- The MCP config out of the workspace and onto the command line.
- The trust and MCP-server seeds, so switching costs no dialogs.

Deliberately cut:

- **The editor.** sora-editor is separate work and does not gate any of this.
- **Persisting SAF grants** and any document-provider file access.
- **Copying or syncing** a shared project into app-private storage.
- **Removable-volume writes** outside the app's own directory there. Derive,
  probe, refuse, offer the app directory on that volume.
- **Recents management** -- no rename, no delete, no eviction beyond the cap.
- **Per-project terminal state.** Switching away loses the screen; switching back
  starts a new agent.
- **More than one project at a time.** One agent, one workspace, one terminal.
- **Surviving rotation.** The agent already dies with the Activity. The session
  holder is where that would be fixed, and moving it out of the Activity is the
  fix, but it is not this change.

## What fights this in the code as it stands

`Agent.kt:22,35,72,79` -- `HOME` and `cwd` are both `context.filesDir`, and
`session()` takes no workspace. The two have to separate; only `cwd` moves.

`MainActivity.kt:64,74-78,83-87` -- one `home` value is passed as workspace, as
lock directory and as MCP config location. Three roles, one variable.

`IdeServer.kt:53-77` -- the lockfile is written from a coroutine after the port
binds, and nothing observes when it exists. This is harmless only because the
spawn happens much later, at `onSurfaceReady`. A respawn has no such delay, and
an agent that starts before the lockfile lands finds no editor.

`IdeServer.kt:85`, `PanelServer.kt:60` -- `stop()` cancels the scope the object
owns, so neither is restartable. Switching must construct new instances.

`PanelServer.kt:78` -- `File(workspace, CONFIG_NAME)` puts the token in the
workspace. This is the file that must move.

`vendor/ghostty-android/android/terminal-library/src/main/cpp/pty.c:152-156` -- a
failed `chdir` prints to the pty and execs anyway, so the agent runs in the app's
inherited cwd. The lockfile then names a directory that is not an ancestor of it
and no editor is found, while the MCP config still loads because it is on the
command line -- which makes the failure quieter still. Validating before spawn is
what keeps this branch unreachable.

`MainActivity.kt:51,89,94,175-196` -- `session` is a field captured by the
`AndroidView` factory closure, and a factory runs once. A replacement session
would never be seen by the input listener or the output callback. The listener
has to read the current session through a holder at call time.

`MainActivity.kt:176-189` with `GhosttyGLSurfaceView.kt:495-498` -- the session is
started only from `onSurfaceReady`, which fires only when the grid size changes.
A respawn gets no such callback, so the geometry has to be remembered.

`MainActivity.kt:69` -- `readFile = { path -> File(path).readText() }` reads any
absolute path the app can read, on a path the agent chooses. With storage
permission granted that is the whole shared volume. This is a trust boundary and
should be confined to the project root and the agent's home.

`Agent.kt:60-62` -- `.claude.json` is seeded only when absent. The per-project
trust key has to merge into an existing file, and only while no agent is running.

`ui/InputToolbar.kt:41-50` -- eight keys already fill the row. The project entry
point does not fit there, which is the other half of the argument for the header.

`Surfaces.kt:75-77` -- nothing to change. Abandoning a diff already rejects it,
which is the correct semantics for a switch. The sequence only has to call
`dismiss()` rather than dropping the object on the floor.

`MainActivity.kt:113-119` -- every server and the agent live and die with the
Activity, which is why rotation kills the agent.

`app/build.gradle.kts` -- two channels are two application ids and two `filesDir`
trees, but one shared volume. Both can open the same project directory at once,
with two agents writing the same files and two lockfiles in two different homes.
Nothing locks a project; that is worth knowing rather than worth fixing here.

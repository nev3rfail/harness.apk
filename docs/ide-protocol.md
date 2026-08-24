# The IDE side of `claude --ide`

What an editor has to do for Claude Code to treat it as an IDE. None of this is
published; it is read out of the CLI's own bundle, so treat it as observed
behaviour of one version rather than a contract.

## Discovery

The CLI scans `$CLAUDE_CONFIG_DIR/ide` — `~/.claude/ide` by default — for files
named `<port>.lock`. **The filename is the port**; nothing inside the file
carries it. Newest modification time wins.

The file is JSON:

```json
{
  "pid": 12345,
  "workspaceFolders": ["/absolute/path"],
  "ideName": "Some Editor",
  "transport": "ws",
  "runningInWindows": false,
  "authToken": "an opaque string"
}
```

`transport: "ws"` selects the WebSocket transport. A file that fails to parse as
JSON is read as a newline-separated list of workspace folders, which is a legacy
shape not worth writing.

## Connecting

The CLI opens a WebSocket to `ws://127.0.0.1:<port>` with:

- subprotocol `mcp`
- header `X-Claude-Code-Ide-Authorization: <authToken>`

Each text frame is one JSON-RPC 2.0 message. So the editor is an MCP server that
happens to speak over a socket it opened itself, and the usual `initialize`,
`tools/list`, `tools/call` exchange follows.

## What the CLI calls on its own

Four methods are invoked by the CLI itself rather than by the model, so an editor
that does not answer them will misbehave in ways the model cannot see:

| Method | When |
| --- | --- |
| `openDiff` | the agent proposes an edit and wants it shown for review |
| `close_tab` | that review is finished with |
| `closeAllDiffTabs` | a turn ends, or the session is tidying up |
| `getDiagnostics` | after edits, to collect problems the editor knows about |

These four are not special-cased in the wire protocol: the CLI reaches them
through `tools/call` like any other tool, so they have to appear in `tools/list`
as well. What makes them different is only who decides to call them.

Everything else an editor exposes is an ordinary MCP tool: it appears in
`tools/list`, the model decides when to call it, and the names are the editor's
own choice. That is the opening this project is built around — a capability the
app can render becomes a tool the agent can reach for.

### openDiff

The one with a real contract. Arguments:

```json
{
  "old_file_path": "/absolute/path",
  "new_file_path": "/absolute/path",
  "new_file_contents": "the proposed text",
  "tab_name": "an identifier the CLI reuses to close it"
}
```

The reply is read positionally out of the `content` array, and the CLI accepts
exactly three shapes:

| `content` | Meaning |
| --- | --- |
| `[{text: "FILE_SAVED"}, {text: "<contents>"}]` | accepted; the second block is what to use, so the user may have edited it |
| `[{text: "DIFF_REJECTED"}]` | declined; the file keeps its old contents |
| `[{text: "TAB_CLOSED"}]` | accepted as proposed |

Anything else raises `Not accepted`. The call is expected to stay open until the
user decides, which makes it the one tool an editor cannot answer immediately.

### getDiagnostics

Called with `{}` for the whole workspace, or `{uri: "file:///path"}` for one
file. The reply is a single text block whose text is JSON:

```json
[{"uri": "file:///path", "diagnostics": []}]
```

An editor with no language server of its own answers with an empty array, which
the CLI reads as "nothing to report" rather than as a failure.

## What the editor sends unprompted

Two notifications travel the other way, editor to CLI:

`selection_changed` — what the user has highlighted, which the CLI folds into
context as an `ide_selection` block.

```json
{
  "method": "selection_changed",
  "params": {
    "selection": {"start": {"line": 0, "character": 0},
                  "end": {"line": 3, "character": 12}},
    "text": "the selected text",
    "filePath": "/absolute/path"
  }
}
```

`at_mentioned` — the user pointed the agent at a file, which the CLI turns into
an `@path#L10-20` mention in the prompt. Lines are zero-based; the CLI adds one.

```json
{
  "method": "at_mentioned",
  "params": {"filePath": "/absolute/path", "lineStart": 9, "lineEnd": 19}
}
```

`selection` may be null, and every field but `filePath` is optional.

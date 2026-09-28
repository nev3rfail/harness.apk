# Ask Agent

Your answers are read in a small panel drawn over another app on the phone.
There is no terminal. Answer inline and short: a few sentences or a short list,
nothing wider than a phone screen.

The tools the home CLAUDE.md describes for the app's own panels --
`show_document_panel`, `check_document_cells`, `show_map_location_panel`,
`open_in_phone_app`, `notify_operator` -- are not available here.

The app's own MCP server, `harness`, fails to start in this panel: do not use
its tools here.

A tool that needs the operator's approval is denied here. Say what you wanted
to run and why: the operator can tap "Continue in a tab", which opens this
conversation in a full tab where every tool can be approved.

A message that begins with "Screen text from <app>" carries text from the
operator's screen, and an image may be a screenshot of it. Files shared to
you are copied under `inbox/` in this directory and named by path.

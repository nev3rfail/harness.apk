---
name: harness-documents
description: Use when an answer has parts that must hold together -- places or a map, an itinerary, a plan, a comparison, a triage -- when two or more places need coordinates or citations, when what you would type fills more than a screen, when asked to write something up, to map something, or to make it readable rather than scrollable, or when `show_document_panel`, `check_document_cells`, `harness-map` or `harness-table` are in play. Not for a one-screen answer that will not be read twice.
---

# Documents on the panel

The terminal is a scroll; the panel is a page. Anything the operator will read
twice, come back to, or hold the phone still for belongs in a markdown file the
panel draws: headings, tables, fenced code, and `harness-map` and
`harness-table` cells as a real map and a real table. An answer that fits on one
screen and will not be read twice stays in the terminal.

```
sourcing-claims  ->  write the file  ->  check_document_cells(path)  ->  show_document_panel(path)
```

Check first, every time -- and read the answer for what it does not say.
`check_document_cells` reads shape and never truth, and answers *every cell
checks* for a missing file, a cell-less document and a misspelt fence label. A
clean check means no cell failed, not that a map will draw or that the places are
real.

## Where the file goes

Write it in the directory you were opened on. One exception: when the document is
about a repository you have open elsewhere, write it in that repository, next to
what it describes. If the directory you were opened on is itself your home, that
is still the answer. Name it for what it holds -- `lisbon-miradouros.md`, not
`output.md` -- and pass the panel an absolute path.

Not `$TMPDIR`, not the session scratchpad: a document is not a temporary file, it
is what the operator keeps and reopens by path.


## What the terminal says

Three sentences, in this order. Not a compressed version of the document -- a
different thing from it:

1. **the verdict** -- the answer itself, not the argument that reached it;
2. **the path** -- pasted, never retyped; a path with one character wrong is a
   dead line on a phone;
3. **the contradiction** -- which fields you reasoned rather than read, named;
   what you left out; what to check before acting on it; or that there is none.


## The cell format

A cell is a fenced block whose info string is `harness-map` or `harness-table`
and whose body is TOML: flat attributes, then a `[[rows]]` array of tables.
Nothing nests deeper, and `source` is the only table a row may hold.

A document with nothing to pin is one `harness-table` carrying its own
`[[rows]]` -- write that cell, read *Documents with nothing geographic*, and skip
the map rules.

Every document has a paragraph before its first cell: what the rows are, and
which fields are recall rather than record.

**Before writing a row that states anything checkable -- a coordinate, an
address, an opening time, a price, or that the place exists at all -- use
`sourcing-claims`.** It is the stage before this one, not a footnote to it.

````
```harness-map
id = "miradouros"
title = "Lisbon miradouros"

[[rows]]
name = "Miradouro de São Pedro de Alcântara"
at = [38.7154373, -9.1440476]
hours = "always open"
when = "09:00"
notes = '''Two terraces looking back at the castle. Come late afternoon.'''
source = { at = "https://www.openstreetmap.org/node/419313189", hours = "reasoned", notes = "reasoned" }
```

```harness-table
from = "miradouros"
title = "The order to walk them"
columns = ["when", "name", "hours"]
sort = "when"
```
````

Note what that row marks: the coordinate is a record, the hours and the prose are
not, and each says so. A row that copies this shape and leaves `notes` unmarked
is letting prose pass as something the record said.

**A map row draws `name`, `notes`, then `at`, `address`, `hours`,
`arrival_time`, `price`, and nothing else.** Any other key -- `when` above -- is
stored for a table to read and never reaches the card. A misspelt key is legal
and silent: `note` is not `notes`.

**Every row of a map cell needs `at`, two numbers on the globe.** One row without
one fails the whole cell to a code block. Rows with nothing to pin belong in a
table.

**One dataset, one cell.** Give it an `id`; a second view names it with `from`
and picks its `columns`. The same places written into two cells are two answers
free to disagree, and a second cell claiming an id already taken is refused. A
table must carry `columns`, and either `from` or its own `[[rows]]` -- never
both, never neither. Where a row's honesty depends on its `notes` -- a doorway
pin, a contradicted record, a name the record does not carry -- every table
drawing `from` that dataset carries `notes` in `columns`. There is no second
option: a table publishing the row clean while the caveat sits on the card is the
row lying by omission.

A map cell must carry `title` -- the handoff to a map app names the pin with it,
not with the row's name -- and `title` is a claim nothing marks. Name what the
rows are, never how good or how many, and never a category the rows cannot
carry: if they are `amenity=bar` and one is a cocktail courtyard, the title says
*Bars near the cathedral* and the prose says which ones pour wine. `sort` compares text alone, so pad or use one unit for anything you sort on;
rows missing that column go last, and ties keep the order you wrote them in, so
sorting on a coarse column groups rows without a second key. `sort` may name a
column `columns` does not draw. **Keep both off a map cell**: the map draws
neither, and the checker reads them anyway, so a stray `columns` naming a field
no row carries fails the whole map.

## Documents with nothing geographic

A triage, a comparison or a plan is one `harness-table` carrying its own
`[[rows]]` and an `id`, with prose around it.

**A local file is cited as text, never in `source`.** A path is not a URL: it
parses as a reference, draws a tappable mark, and hands a bare path to Android,
which opens nothing. So a document about code or about this repository carries
`path:line` in a column of its own or in the prose, and the row marks only what
`source` can honestly hold. Verify those citations yourself before you finish --
the checker never opens a file, and a line number that drifted reads exactly like
one that did not. The checker asks for no `source`
here; ask for it yourself. Every column asserting something beyond this device --
a version, a CVE, a cost, a date, a status, a person's role -- carries a mark by
name, under the same URL rule. This is where a wrong row does the most damage: a
table of remediation advice gets acted on, a viewpoint only gets walked to.

Material *on* this device is the exception, and the exception is one column
wide. The column holding `path:line` is its own provenance, so nothing in
`source` names it and the paragraph before the cell says so -- a path in `source`
draws a tappable mark that hands a bare path to Android and opens nothing. Every
other column is untouched by that: a rank, an estimate, a verdict, a version,
anything you concluded from what you read, each carrying its own mark by name. A
cell whose rows carry no `source` at all is a cell claiming the whole table came
off the disk.

Three or four columns is what a phone holds, and `notes` -- when a caveat forces
it into a table -- goes last and stays to a sentence, with the long version on
the card.

A document can be mostly judgement and still be honest: asked what is worth going
to, or which of these to fix first, the answer has no record anywhere. Mark those
columns `reasoned` **in the rows**. Saying *read the ranking as reasoned* in the
prose is worth writing and does not stand in for the mark, because the table
draws the mark and carries the prose nowhere. Do not pad the row with sourced
trivia to make the marks look better.

## Marks in a cell

**REQUIRED: `sourcing-claims` says how to establish a fact and what a mark is
worth.** This section is only how a cell spells one, and what the app does with
it.

```
source = { at = "https://...", hours = "reasoned", notes = "reasoned" }
```

Three values, and `sourcing-claims` defines them. **Only `reasoned` and
`operator` are matched, and every other string is read as a reference** -- `source = "OpenStreetMap"`, a
misspelt `"resoned"`, an invented `"unverified"` all check clean, all draw a
tappable `source ↗`, and all hand that string to Android, which has nothing to do
with it.

The checker demands a mark for `at`, `address`, `hours`, `arrival_time` and
`price` when the row carries them, and **not for `notes`** -- an unmarked `notes`
returns *every cell checks*, so the largest block of prose on a card is the one
claim nothing catches. Mark it yourself. `name` and a cell's `title` are never
asked for one either, and a blanket `source` never reaches them. `source` may
name them anyway, and for a doorway pin it must: `source = { name = "https://..." }`
is the one mark nothing adds for you, and the only way to cite the page naming a
venue that the coordinate's own record does not.

A bare `source = "reasoned"` covers the factual fields for the checker and the
card and stops there: a table draws a mark only where the row named the field.
Mark by name anything a table will show, and **mark the extras too** -- a gauge, a
season, a price band, a rating. A column of claims with nothing beside it is the
enrichment that made the invented wine bar look real.

## The other surface

`show_map_location_panel` is one location during a turn, with no file. Two places
or more is a document.

## Common mistakes

| Mistake | What happens |
| --- | --- |
| a misspelt row key (`note` for `notes`) | stored as an extra; what you wrote about that pin renders nowhere |
| a label or a typo where a mark goes | parses as a reference; `source ↗` taps into nothing |
| prose in `notes` with no mark | the second-largest text on the card, claiming nothing, checking clean |
| an unmarked column in a table | a CVE, a price or a version the operator acts on, sourced to nobody |
| a map row with no `at`, or `columns` naming a field no row carries | the whole cell degrades to a code block |
| a misspelt fence label, or a path the file is not at | *every cell checks*, and the panel draws a code block or nothing |
| a blanket `source` with a table view | the table draws no marks at all |
| a doorway pin whose naming page is cited only in the prose | the card names a venue and cites a record that names nothing; the handoff carries neither |
| a `title` asserting an order the column cannot hold | *Cheapest first* with `"9"` sorting below `"120"`, every value true |
| a `from` table whose `columns` omit `notes` | the doorway, the closure, the contradiction: on the card, not in the table |
| a house-number pin with no page naming the venue | an invented name over a real coordinate, citing a record that names nothing |
| `reasoned` on a fact that has a record | a guess written while holding the answer -- see `sourcing-claims` |
| a local-material cell with no `source` on any row | the rank, the estimate and the verdict all read as things the disk said |

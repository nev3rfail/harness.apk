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
write the file  ->  check_document_cells(path)  ->  show_document_panel(path)
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
2. **the path**;
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
drawing `from` that dataset carries `notes` in `columns`, or the caveat lives on
the card while the table publishes the row clean.

A map cell must carry `title` -- the handoff to a map app names the pin with it,
not with the row's name -- and `title` is a claim nothing marks. Name what the
rows are, never how good or how many: *Wine bars near Plaça de la Reina*, not
*The three best wine bars in Palma*. `sort` compares text alone, so pad or use one unit for anything you sort on, and
expect rows missing that column last; it may name a column `columns` does not
draw. A map cell draws neither, but **the checker reads both on any cell**, so
either one naming a field no row carries fails the whole map.

## Documents with nothing geographic

A triage, a comparison or a plan is one `harness-table` carrying its own
`[[rows]]` and an `id`, with prose around it. The checker asks for no `source`
here; ask for it yourself. Every column asserting something about the world
outside this conversation -- a version, a CVE, a cost, a date, a status, a
person's role -- carries a mark by name, on the same values, under the same URL
rule. This is where a wrong row does the most damage: a table of remediation
advice gets acted on, a viewpoint only gets walked to. Three or four columns is
what a phone holds.

## Provenance

`at`, `address`, `hours`, `arrival_time`, `price` and `notes` are claims about
the world, and each needs a mark inside the row that makes it:

```
source = { at = "https://...", hours = "reasoned", notes = "reasoned" }
```

The checker enforces this for `at`, `address`, `hours`, `arrival_time` and
`price`, and **not for `notes`** -- an unmarked `notes` returns *every cell
checks*. The largest unmarked claim on the card is the one nothing will catch.

Three values: a URL, `reasoned` (you recalled or inferred it), or `operator` (the
person in the conversation said it). Only those two words are matched, and
**every other string is read as a reference** -- `source = "OpenStreetMap"`, a
misspelt `"resoned"`, an invented `"unverified"` all check clean, all draw a
tappable `source ↗`, and all hand that string to Android, which has nothing to do
with it.

`reasoned` means recalled or inferred and stands behind the claim. It does not
stretch to something filled in because the column looked empty. That field comes
out of the row.

A bare `source = "reasoned"` satisfies the checker and the card, but a table
draws a mark only where the row named the field. Mark by name anything a table
will show, and **mark the extras too** -- a gauge, a season, a price band, a
rating. A column of claims with nothing beside it is the enrichment that made the
invented wine bar look real.

**A URL is a record you actually read this turn.** A search link
(`.../search?q=...`) is a query, not a record: it vouches for nothing and the tap
runs a search. A URL that only appeared in a list of search results is not one
either. Open it with one `WebFetch` and cite what you then read -- but expect
that to fail: Tripadvisor, Yelp, Google and Foursquare answer `WebFetch` with
403, which is most of what carries hours and prices. When the fetch is closed,
`reasoned` is the normal answer rather than the fallback, and the terminal says
no page was opened. The same goes for a page a subagent opened and you did not:
that is `reasoned`, and the caveat says a research pass found it.

An API response and its object page are one record, so citing
`openstreetmap.org/way/146283133` for a way the geocoder just handed you is
citing what you read, not a page you skipped. That covers what the response
carried -- the name, the coordinate -- and stops there: `hours` or `address`
under that same URL is a tag you never read.

**The name is the claim nothing checks.** No rule asks `name` for a source and a
blanket `source` never reaches it -- only a hand-written `source = { name =
"..." }` puts a mark beside it -- and it is the largest text on the card. A row
whose `at` cites a record for a *different* place is invention with a citation
bolted on.

The name you write is the name on the record you cited, with one exception: where
the operator's name is not the record's name, carry both --
`name = "Pink Street (Rua Nova do Carvalho)"`, citing the way. Never the common
name alone, and never let a search for one hand you a neighbour: *Pink Street*
returns the bar *Velha Senhora* first, and that node is not the street.

## Get the coordinate before you write the row

Not from memory, and not from the first hit:

```sh
geo() {
  body=$(curl -s -A harness-documents --max-time 20 -w '\n%{http_code}' \
    "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=3&q=$1")
  code=${body##*$'\n'}; json=${body%$'\n'*}
  [ "$code" = 200 ] && [ -n "$json" ] || { echo "THROTTLED ($code) -- retry, one at a time"; return; }
  echo "$json" | jq -r 'if length==0 then "NO MATCH" else .[] |
    "\(if (.name|length)==0 then "(unnamed)" else .name end) | \(.osm_type)/\(.osm_id) | \(.lat),\(.lon) | \(.display_name)" end'
}
geo "Miradouro+de+Sao+Pedro+de+Alcantara"
```

Up to three results with the name beside each -- `limit=3` is a ceiling, and one
hit is a good answer. **Read the name that came back.** `Miradouro da Senhora do
Monte` answers with *Miradouro da Quinta da Fidalga*, ten kilometres away across
the river: a real record, a real link, the wrong place.

`NO MATCH` is *not found*, and a real place vanishes under the name anyone would
type -- try the official name, drop the city, try the building rather than the
viewpoint before believing it. `THROTTLED` is the public endpoint, not absence:
retry. Without that guard the two are indistinguishable and a throttle reads as
proof the place does not exist.

`(unnamed)` is an address way or a junction. There is no name on it to copy, so
do not invent one: name the row for what the operator asked about and say in
`notes` that the pin is an address record.

Build the citation from `osm_type` and `osm_id` together --
`https://www.openstreetmap.org/way/146283133` for a way, `/node/...` for a node.
A way's id under `/node/` is a different object or none at all.

Nominatim knows venues and streets, not lines, valleys or institutions; for those
a Wikipedia article carrying coordinates is a record you cite the same way.
For places *near* something rather than named, ask Overpass instead:

```sh
near() {
  body=$(timeout 40 curl -s -A harness-documents -w '\n%{http_code}' \
    --data-urlencode "data=$1" https://overpass-api.de/api/interpreter)
  code=${body##*$'\n'}; json=${body%$'\n'*}
  [ "$code" = 200 ] && [ -n "$json" ] || { echo "THROTTLED ($code) -- retry"; return; }
  echo "$json" | jq -r 'if (.elements|length)==0 then "NO MATCH" else .elements[] |
    "\(.tags.name // "(unnamed)") | \(.type)/\(.id) | \(.lat // .center.lat),\(.lon // .center.lon)" end'
}
near '[out:json][timeout:25];nwr(around:400,39.5680,2.6485)[amenity=bar];out center tags 20;'
```

`NO MATCH` and `THROTTLED` mean here what they mean above, and the guard is the
point: unguarded, a throttled Overpass prints a `jq` parse error and an empty one
prints nothing, and both read as a place that is not on the map.

**If nothing comes back under any name you can justify, do not write the row** --
unless a page you opened this turn says the venue is there, which happens
constantly with anything that opened recently or is filed under a tag nobody
would search. Then pin its house number, cite that node for `at`, cite the page
you read on `name` -- `source = { name = "https://..." }`, the one mark nothing
adds for you -- and say in `notes` that the pin is a doorway rather than a record
naming the venue. Recall is not attestation: with no page, there is no row.

A pin can also be real and the door shut: a venue you confirmed is closed comes
out of the map and goes in the prose, with the page that says so.

## Read the row back before you leave it

Before the closing brace of `source`:

- `name` is the name on the record `source.at` points to, or carries both names;
  where the record does not name the venue, `notes` says so **and** `source.name`
  cites the page that does;
- the coordinate is the one that record gave, digit for digit;
- every field the card draws says where it came from, `notes` included, and the
  ones that came from you say `reasoned` rather than borrowing the row's URL;
- anything you could not confirm is in `notes`, or is not in the row;
- then name the `reasoned` fields in the terminal's third sentence. If that
  sentence is empty, you opened a page for every field or you skipped this.

A row naming a bar that does not exist, at a real neighbour's coordinate, citing
that neighbour's real node, checks clean. This is the only review there is.

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
| a `from` table whose `columns` omit `notes` | the doorway, the closure, the contradiction: on the card, not in the table |
| a house-number pin with no page naming the venue | an invented name over a real coordinate, citing a record that names nothing |

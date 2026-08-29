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
stretch to something filled in because the column looked empty -- that field comes
out of the row. A structural fact with no record anywhere, like an open terrace
having no gate, is `reasoned`, and the prose says which kind it is.

**A mark covers the field's whole value.** If half of it came from the page and
half from you, the half that came from you is a separate claim with nowhere to
mark it, so it comes out. `hours = "Mo-Su 13:00-23:00"` under the venue's page is
honest; the same string with `; wine service continues after the kitchen closes`
appended is invention wearing a citation.

**Records contradict each other, and that is a finding.** Two OSM nodes for one
address with different hours, a first-party page disagreeing with a tag: take the
better record and say in `notes` which you took and what the other said, or drop
the field if you cannot tell. Never average them, never choose silently.

A bare `source = "reasoned"` satisfies the checker and the card, but a table
draws a mark only where the row named the field. Mark by name anything a table
will show, and **mark the extras too** -- a gauge, a season, a price band, a
rating. A column of claims with nothing beside it is the enrichment that made the
invented wine bar look real.

**A URL is a record you actually read this turn.** A search link
(`.../search?q=...`) is a query, not a record: it vouches for nothing and the tap
runs a search. A URL that only appeared in a list of search results is not one
either. The same goes for a page a subagent opened and you did not: that is
`reasoned`, and the caveat says a research pass found it.

**A fact with a record is never `reasoned`.** `reasoned` is for judgement and
recall -- which viewpoint is worth the climb, how long the walk feels. A closing
time, an address, a price exists somewhere; writing `reasoned` over it is
guessing while holding the answer. When `WebFetch` is refused, climb:

1. **The record you already have.** The geocoder handed you an object id, and
   the object carries tags the search result never showed you:
   `curl -s https://api.openstreetmap.org/api/0.6/node/6960339686.json | jq '.elements[0] | {version, timestamp, tags}'`
   answers `opening_hours`, `phone` and `website` in the same call as the age of
   the record. Cite the object -- and read that age: a `v1` node nobody has
   touched in years, especially one whose name and street are duplicated on a
   second node nearby, is an abandoned copy. The live twin is the edited one, and
   it is often the one *without* hours. Prefer no hours to a dead node's hours.
2. **The `website` tag, first-party.** A venue's own page beats any aggregator
   and is rarely walled.
3. **`curl -sL` where `WebFetch` was refused.** They are not the same client:
   Michelin and Google answer `curl` with 200. Follow redirects -- a site that
   answers 301 with no body has not refused you. And **do not spoof a
   user-agent**: measured here, Michelin returns 600 KB to plain `curl` and *zero
   bytes* to the same request wearing `-A 'Mozilla/5.0'`. A fake browser string
   is a bot signature, not a disguise.
4. **A real browser**, for a page whose content JavaScript draws.

   ```sh
   chromium-browser --headless --no-sandbox --disable-gpu --disable-dev-shm-usage \
     --virtual-time-budget=12000 --dump-dom "$1" 2>/dev/null
   ```

   The stderr redirect is not optional -- it writes a screenful of dbus failures
   that mean nothing. Two things to know before you spend twenty seconds on it.
   **It fails silently**: a domain that does not resolve returns 188 KB of
   offline page, which looks like a fetch that worked, so grep the dump for the
   content you came for rather than trusting its size. And **the ladder is not
   monotonic** -- Google answers `curl` with 92 KB of results and this with 6 KB
   of consent shell. Re-check a cheap rung before believing the expensive one is
   needed. Tripadvisor and Yelp serve it a CAPTCHA exactly as they serve `curl` a
   403: those two are a wall, and no rung here climbs them. Firefox already open
   on the phone can be driven through its debugger socket instead; see
   `firefox-remote-debug`.

**If Chromium is not installed, ask before installing it.** `apt install
chromium` is about 190 MB over the operator's own connection, and not every phone
has the room. Say what the fact is, say what the install costs, and wait.

**If the operator says no**, write `reasoned`, and put the refusal in that row's
`notes` as well as in the terminal -- *hours unverified; the check that would
have settled them was declined*. The terminal is scrollback and the document is
what gets reopened by path, so a caveat living only in the terminal is gone by
the time anyone acts on the claim.

**A field you cannot settle is not a row you cannot write.** One thing alone
takes a row off a map: not being able to establish that the place is there. A
place you have verified whose closing time you could not is a row with no `hours`
field and a `notes` saying the hours could not be established and what you tried.
The field goes; the row stays. A bar with an unknown closing time is still worth
a pin, and deleting it tells the operator less than an honest blank does.

Which fields are load-bearing is decided by the request, not by the schema. Asked
for somewhere open late, the hours *are* the answer, and a row that cannot carry
them belongs in the prose under what could not be settled rather than on the map
as though it qualified.

That order matters. A softened mark reached without climbing is how an invented
place ends up in a plan somebody acts on.

An API response and its object page are one record, so citing
`openstreetmap.org/way/146283133` for a way the geocoder just handed you is
citing what you read, not a page you skipped. The bound is what the response
actually carried: a geocoder hands back a name and a coordinate, while an
Overpass query with `out tags` hands back the address and the hours too, and
those are read. A tag that was not in the response you got is not.

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
  body=$(curl -s -A harness-documents --max-time 20 -w '\n%{http_code}' --get \
    --data-urlencode "q=$1" --data 'format=jsonv2&limit=3' \
    "https://nominatim.openstreetmap.org/search")
  code=${body##*$'\n'}; json=${body%$'\n'*}
  case "$code" in
    200) [ -n "$json" ] || { echo "NO REPLY -- retry once"; return; } ;;
    000) echo "NO REPLY -- network, not a throttle"; return ;;
    4*)  echo "BAD REQUEST ($code) -- fix the query; retrying will not help"; return ;;
    *)   echo "THROTTLED ($code) -- retry, one at a time"; return ;;
  esac
  echo "$json" | jq -r 'if length==0 then "NO MATCH" else .[] |
    "\(if (.name|length)==0 then "(unnamed)" else .name end) | \(.osm_type)/\(.osm_id) | \(.lat),\(.lon) | \(.display_name)" end'
}
geo "Miradouro de Sao Pedro de Alcantara"
```

**Never paste a name into the URL yourself** -- that is what `--data-urlencode`
is for. Unencoded, a space fails the request and reports as a throttle, an accent
returns 400, and an `&` truncates the query silently: `Marks & Spencer Palma`
becomes a search for `Marks` and answers 200 with a relation in Mississippi. A
real record, a real link, the wrong continent, and no error at all.

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
    "\(.tags.name // "(unnamed)") | \(.type)/\(.id) | \(.lat // .center.lat),\(.lon // .center.lon) | \(.tags.opening_hours // "-")" end'
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
- where you climbed and failed, `notes` says so -- nothing else records the
  attempt, so a `reasoned` reached after four refusals and one typed in a second
  read identically on the page;
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
| `reasoned` on a fact that has a record | a guess written while holding the answer -- climb, or drop the field |

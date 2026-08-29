---
name: sourcing-claims
description: Use before writing down anything checkable about the world -- an address, an opening time, a price, a coordinate, a version, a CVE, whether a place exists at all -- and whenever a fetch is refused, two records disagree, or you are about to mark something as recalled rather than read.
---

# What vouches for a claim

A fact you did not check is a guess in your own voice, and it reads exactly like
one you did check. This is the discipline that keeps the two apart, and the
rungs at the bottom are how it is done on this device.

The failure it exists for is not carelessness. Asked to map wine bars in Palma,
an agent produced a venue that does not exist: a plausible name, a plausible
address, coordinates in the right city. It was perfectly well formed and no
schema would have caught it. The operator did.

## Say where every value came from

Three marks, and only three:

| mark | means |
| --- | --- |
| a URL | a record you opened and read **this turn** |
| `reasoned` | you recalled or inferred it and stand behind it |
| `operator` | the person in the conversation said so |

On a map row a caveat lives in `notes`, not in the document's prose. The card
draws `notes`; it does not draw the prose around the cell, and the handoff to a
map app carries neither. A caveat written anywhere else is a caveat the operator
does not have at the moment they act.

`reasoned` is for judgement and recall -- which viewpoint is worth the climb, how
long a walk feels, a structural fact with no record anywhere like an open terrace
having no gate. It does not stretch to something filled in because a column
looked empty. That value comes out.

**A fact that has a record is never `reasoned`.** A closing time, an address, a
price exists somewhere; writing `reasoned` over it is guessing while holding the
answer.

**A value you worked out is `reasoned`, and say what from.** A distance computed
from two coordinates you read this turn is neither recall nor a record: the
inputs are cited and the arithmetic is yours. Mark it `reasoned` and name the
derivation, so the operator can see it is a calculation rather than a guess.

**A mark covers the value's whole extent.** If half came from the page and half
from you, the half from you is a separate claim with nowhere to mark it, so it
comes out. `Mo-Su 13:00-23:00` under the venue's page is honest; the same string
with `; wine service continues after the kitchen closes` appended is invention
wearing a citation.

**A URL is a record you actually read.** A search link (`.../search?q=...`) is a
query, not a record -- it vouches for nothing and a tap on it runs a search. A URL
that only appeared in a list of search results is not one either, and neither is
a page a subagent opened and you did not: that is `reasoned`, and the caveat says
a research pass found it.

An API response and its object page are one record, so citing
`openstreetmap.org/way/146283133` for a way a geocoder just handed you is citing
what you read. The bound is what the response actually carried -- a geocoder
returns a name and a coordinate, an Overpass query with `out tags` returns the
address and the hours as well, and those are read. A tag that was not in the
response you got is not.

**The name is the claim nothing checks.** Nothing asks a name for a source. A
record cited for a *different* place is invention with a citation bolted on: the
link opens, the coordinate is real, the venue is invented. The name you write is
the name on the record you cited -- with one exception, where the operator's name
is not the record's: carry both, `Pink Street (Rua Nova do Carvalho)`, and cite
the way. Never the common name alone, and never let a search for one hand you a
neighbour: *Pink Street* returns the bar *Velha Senhora* first, and that node is
not the street.

## When a fetch is refused, climb

`WebFetch` being refused is not the same as there being no page.

1. **The record you already have.** A geocoder handed you an object id, and the
   object carries tags the search result never showed you:

   ```sh
   curl -s https://api.openstreetmap.org/api/0.6/node/1502055318.json \
     | jq '.elements[0] | {version, timestamp, tags}'
   ```

   That one answers `opening_hours` and `website` where the record carries them,
   in the same call as the age of the record -- and read the age. `node/6961445391`
   is `v1`, untouched since 2019, duplicated by a second node at the same address
   and phone, and it is the only one of the pair still asserting hours: a wine bar
   shutting at 18:00 on Saturday. An abandoned copy holds whatever was true when
   somebody stopped caring. The live twin is the edited one, and it is often the
   one *without* hours. Prefer no hours to a dead node's hours.
2. **The `website` tag, first-party.** A venue's own page beats any aggregator
   and is rarely walled. Open it before citing it: an OSM `website` tag can have
   gone stale into somebody else's parked blog.
3. **`curl -sL` where `WebFetch` failed for any reason.** Refusal is only one of
   them: a broken TLS chain reads as a dead site and often answers fine over
   plain `http`, and a host that does not answer at all is not the same as one
   that turned you away. They are not the same client, and
   redirects matter -- a site answering 301 with no body has not refused you. **Do
   not spoof a user-agent:** measured here, Michelin returns 600 KB to plain
   `curl` and *zero bytes* to the same request wearing `-A 'Mozilla/5.0'`. A fake
   browser string is a bot signature, not a disguise.
4. **A real browser**, for a page whose content JavaScript draws.

   ```sh
   chromium-browser --headless --no-sandbox --disable-gpu --disable-dev-shm-usage \
     --virtual-time-budget=12000 --dump-dom "$1" 2>/dev/null
   ```

   The stderr redirect is not optional; it writes a screenful of dbus failures
   that mean nothing. **It fails silently** -- a domain that does not resolve
   returns 188 KB of offline page, which looks like a fetch that worked, so
   search the dump for the content you came for rather than trusting its size.
   And **the ladder is not monotonic**: Google answers `curl` with 92 KB of
   results and this with 6 KB of consent shell. Re-check a cheap rung before
   believing the expensive one is needed. Tripadvisor and Yelp serve a CAPTCHA
   here exactly as they serve `curl` a 403 -- those two are a wall, and nothing on
   this list climbs them. Firefox already open on the phone can be driven through
   its debugger socket instead; see `firefox-remote-debug`.

That order matters. A softened mark reached without climbing is how an invented
place ends up in a plan somebody acts on.

**If Chromium is not installed, ask before installing it.** `apt install
chromium` is about 190 MB over the operator's own connection, and not every phone
has the room. Name the fact you are chasing, name the cost, and wait.

## When records disagree

Two records for one address with different hours, a first-party page
contradicting a tag: that is a finding, not a nuisance. Take the better record
and say in `notes` which you took and what the other said, or drop the value if you cannot
tell. Never average them and never choose silently.

## When you cannot settle it

**If the operator declined the check**, write `reasoned` and put the refusal in
the row's `notes` as well as the terminal -- *hours unverified; the check that
would have settled them was declined*. The terminal is scrollback and the file is
what gets reopened, so a caveat living only in the terminal is gone by the time
anyone acts on it.

**A value you cannot settle is not a row you cannot write.** One thing alone
removes a row: not being able to establish that the thing is there at all. A
place you have verified whose closing time you could not is a row with no hours
and a `notes` saying what you tried. The value goes; the row stays. A bar with an
unknown closing time is still worth a pin, and deleting it tells the operator
less than an honest blank does.

Which values are load-bearing is decided by the request, not by the schema. Asked
for somewhere open late, the hours *are* the answer, and a row that cannot carry
them belongs in the prose under what could not be settled rather than in the list
as though it qualified.

A thing can also be real with its door shut: something you confirmed is closed
comes out of the map and goes into the prose, with the page that says so.

## Finding a place at all

Not from memory, and not from the first hit:

```sh
geo() {
  body=$(curl -s -A harness --max-time 20 -w '\n%{http_code}' --get \
    --data-urlencode "q=$1" --data 'format=jsonv2&limit=3' \
    "https://nominatim.openstreetmap.org/search")
  code=${body##*$'\n'}; json=${body%$'\n'*}
  case "$code" in
    200) [ -n "$json" ] || { echo "NO REPLY -- retry once"; return; } ;;
    000) echo "NO REPLY (000) -- a malformed query, a dead host, or the network, in that order"; return ;;
    4*)  echo "BAD REQUEST ($code) -- fix the query; retrying will not help"; return ;;
    *)   echo "THROTTLED ($code) -- retry, one at a time"; return ;;
  esac
  echo "$json" | jq -r 'if length==0 then "NO MATCH" else .[] |
    "\(if (.name|length)==0 then "(unnamed)" else .name end) | \(.osm_type)/\(.osm_id) | \(.lat),\(.lon) | \(.display_name)" end'
}
geo "Miradouro de Sao Pedro de Alcantara"
```

**Never paste a name into a URL yourself** -- that is what `--data-urlencode` is
for. Unencoded, a space fails the request and reports as a throttle, an accent
returns 400, and an `&` truncates the query silently: `Marks & Spencer Palma`
becomes a search for `Marks` and answers 200 with a relation in Mississippi. A
real record, a real link, the wrong continent, and no error at all.

**Read the name that came back.** `Miradouro da Senhora do Monte` answers with
*Miradouro da Quinta da Fidalga*, ten kilometres away across the river.

`NO MATCH` is *not found*, and a real place vanishes under the name anyone would
type -- try the official name, drop the city, try the building rather than the
viewpoint before believing it. `THROTTLED` is the endpoint, not absence: retry,
one query at a time. `(unnamed)` is an address way or a junction: there is no
name on it to copy, so do not invent one -- name the row for what the operator
asked about, cite the page that names it under `source.name`, and say in `notes`
that the pin is an address record.

Build the citation from `osm_type` and `osm_id` together --
`https://www.openstreetmap.org/way/146283133` for a way, `/node/...` for a node.
A way's id written under `/node/` is a different object or none at all.

Nominatim knows venues and streets, not lines, valleys or institutions; for those
a Wikipedia article carrying coordinates is a record you cite the same way. For
things *near* something rather than named, ask Overpass:

```sh
near() {
  body=$(timeout 40 curl -s -A harness -w '\n%{http_code}' \
    --data-urlencode "data=$1" https://overpass-api.de/api/interpreter)
  code=${body##*$'\n'}; json=${body%$'\n'*}
  case "$code" in
    200) [ -n "$json" ] || { echo "NO REPLY -- retry once"; return; } ;;
    4*)  echo "BAD REQUEST ($code) -- fix the query; retrying will not help"; return ;;
    *)   echo "THROTTLED ($code) -- retry, one at a time"; return ;;
  esac
  echo "$json" | jq -r 'if (.elements|length)==0 then "NO MATCH" else .elements[] |
    "\(.tags.name // "(unnamed)") | \(.type)/\(.id) | \(.lat // .center.lat),\(.lon // .center.lon) | \(.tags.opening_hours // "-")" end'
}
near '[out:json][timeout:25];nwr(around:400,39.5680,2.6485)[amenity=bar];out center tags 20;'
```

`NO MATCH` and `BAD REQUEST` and `THROTTLED` mean here what they mean above, and
a 400 is usually an unquoted colon in a tag key -- `[addr:housenumber]` where
Overpass wants `["addr:housenumber"]`. Serialise these; two in one call gets the
second throttled. Unguarded, a
throttled Overpass prints a `jq` parse error and an empty one prints nothing, and
both read as a place that is not on the map.

**If nothing comes back under any name you can justify, do not write the row** --
unless a page you opened this turn says the thing is there, which happens
constantly with anything that opened recently or is filed under a tag nobody
would search. Then pin the house number, cite that node for the coordinate, cite
the page you read for the name, and say in `notes` that the pin is a doorway rather than a record
naming the venue. Recall is not attestation: with no page, there is no row.

## Read it back before you leave it

Every fact is in front of you while you write the row and nowhere near you
afterwards, so the review happens here:

- the name is the name on the record you cited, or carries both names, or says
  why not and cites the page that names it;
- the coordinate is the one that record gave, digit for digit;
- every value says where it came from, and the ones that came from you say
  `reasoned` rather than borrowing a URL from the row;
- anything you could not confirm is in `notes`, or is not in the row;
- where you climbed and failed, say so -- nothing else records the attempt, so a
  `reasoned` reached after four refusals and one typed in a second read
  identically on the page;
- then name the `reasoned` values in the terminal's third sentence -- the
  contradiction sentence `harness-documents` requires; where there is no
  document, say it in the terminal all the same. If that list is empty, you
  opened a page for every value or you skipped this review.

A row naming a bar that does not exist, at a real neighbour's coordinate, citing
that neighbour's real node, passes every automatic check there is. This review is
the only one that would catch it.

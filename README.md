# WallpaperExtensions

Extension repository for the [Cloudimage](https://github.com/alamsamir7666-ux/Cloud-Wallpaper)
Android app — installable wallpaper-source packages, published to this repo's
`gh-pages` branch.

The repository ships five keyless scrapers, all modeled on the two-layer
Provider/Extractor pattern used by CloudStream plugins: the star,
**`cloudimage.wallpapercave`**, its sharp-eyed sibling
**`cloudimage.hdqwalls`** — HD, 4K, 5K and 8K wallpapers from hdqwalls.com,
the friendliest scraping target in the set (real pagination everywhere,
direct search results, plain JPEG thumbnails) —
**`cloudimage.wallpapers4k`** — the resolution-first library at
4kwallpapers.com, whose schema.org markup hands over title, tags and
originals with unusual candor, and whose search now scrolls the exact
load-more stream its own website serves — **`cloudimage.alphacoders`** —
Wallpaper Abyss at alphacoders.com, the deepest library of the set,
whose listings disclose the original file itself, right in the grid,
and whose real search endpoint delivers exactly what its own website
shows — and **`cloudimage.wallpapersafari`** — the gallery-first library
at wallpapersafari.com, whose topic galleries serve their complete
walls server-rendered, whose cards disclose the original, the uploader
and the file's TRUE dimensions in one grid cell, and whose search is
the site's own, read through the same two-step its album-first model
demands.

## Install in the app

1. Open **Cloudimage → Extensions → Add repository**
2. Paste this repo's URL:
   `https://github.com/alamsamir7666-ux/WallpaperExtensions`
3. The app resolves it to the published index
   (`raw.githubusercontent.com/…/WallpaperExtensions/gh-pages/index.json`),
   verifies every package's SHA-256 on download, and installs it as a
   runtime-loaded provider.
4. **Install** → *WallpaperCave*, *HDQWalls*, *4K Wallpapers*,
   *Alpha Coders*, *WallpaperSafari*, or any mix. No API key, no
   account — each reads its site the way the site's own browser UI does.

## Packages

| Package | Version | Size | Capabilities | Notes |
|---|---|---|---|---|
| `cloudimage.wallpapercave` | 1.2.0 | 24 KB | popular, latest, search, filters, tags | Keyless scraper, SFW, API v1 |
| `cloudimage.hdqwalls` | 1.0.1 | 18 KB | popular, latest, search, filters, tags, random | Keyless scraper, SFW, API v1 |
| `cloudimage.wallpapers4k` | 1.1.0 | 18 KB | popular, latest, search, filters, tags | Keyless scraper, SFW, API v1 |
| `cloudimage.alphacoders` | 1.1.0 | 18 KB | popular, latest, search, filters, tags | Keyless scraper, SFW, API v1 |
| `cloudimage.wallpapersafari` | 1.0.1 | 26 KB | popular, search, filters, tags | Keyless scraper, SFW, API v1 |

### 1.2.0 — a full shelf of browse tabs

The home tab bar grew from three tabs to **thirteen**. Latest Uploads and
Anime/People keep their routing; ten new shelves arrive as tag-style `query`
presets (the host's v1.0.15 mechanism for feeds outside its category
vocabulary), each walking its own category feed — the site's curated topic
first, then that category's album stream, three albums per page:

| Tab | Page 1 (curated) | Pages 2+ (album stream) |
|---|---|---|
| Anime | `anime-wallpapers` (41) | `/categories/anime-manga` (189 albums) |
| Girls | `girls-wallpapers` (122) | `/search?q=girls` (48 albums) |
| Cars | `cars-wallpapers` (79) | `/categories/vehicles/cars` (49 albums) |
| People | `people-wallpapers` (50) | `/categories/people` (128 albums) |
| Games | `games-wallpapers` (52) | `/categories/games` (134 albums) |
| Movies | `movies-wallpapers` (91) | `/categories/movies` (102 albums) |
| Nature | `nature-wallpapers` (78) | `/categories/nature` (46 albums) |
| Space | `space-wallpapers` (85) | `/categories/universe` (42 albums) |
| Animals | `animals-wallpapers` (75) | `/categories/nature/animals` (51 albums) |
| Bikes | `motorcycles-wallpapers` (83) | `/categories/vehicles/motorcycles` (71 albums) |
| Sports | `sports-wallpapers` (76) | `/categories/sports` (73 albums) |
| Abstract | `abstract-wallpapers` (98) | `/categories/abstract` (29 albums) |

A search that names a tab exactly (`"cars"`, `"Girls"`) walks the same feed —
the site itself answers such terms with its category pages (`/search?q=cars`
redirects to the cars category), so this is its own behavior with a better
first page. Longer queries still ride the album merge.

### 1.1.0 — tabs that scroll, images that decode

Two fixes over 1.0.0, both found by live-testing what the app actually
receives:

- **Anime / People tabs ended after ~40 wallpapers.** They served a single
  curated topic and stopped. A category now walks the site's own category
  pages (`/categories/anime-manga`, 189 albums; `/categories/people`,
  128 albums): the curated topic first, then its album stream merged three
  albums per page — the same machinery search uses, so the rows scroll as
  deep as search does.
- **Latest Uploads showed only skeletons.** The feed's `/uwpr/` thumbnails
  are served as **AVIF regardless of the Accept header** — Android below 12
  cannot decode them, so every card shimmered forever. Grid items now carry
  the original file (`/wp/` / `/uwp/`, a plain JPEG/PNG) in both fields —
  the same choice topic items already make, and the one URL every device
  renders.

## How this repository works

**Packages.** Each extension is a zip: an `extension.json` manifest plus a
`classes.dex` produced by `d8` (`--min-api 26`). The dex contains *only the
plugin's own classes* — the `provider/api` contract (and everything else) is
supplied by the host app at runtime through the extension class loader, which
keeps payloads tiny and the contract authoritative.

**Index.** [`gh-pages/index.json`](https://raw.githubusercontent.com/alamsamir7666-ux/WallpaperExtensions/gh-pages/index.json)
advertises every package with `id`, `fileName`, `sha256`, `sizeBytes`,
`versionName/Code`, `apiVersion`, `author`, `description`. The app downloads a
package from the same directory as the index and refuses bytes that don't match
the promised SHA-256.

**Sources live on `main`; artifacts live on `gh-pages`.** The publish pipeline
is reproducible: CI rebuilds every package from source on each push.

```
providers/wallpapercave/     extension source (Kotlin JVM module)
├── build.gradle.kts         applies the cloudimage.provider convention
├── extension.json           manifest: id, version, entry class
└── src/…                    parser + provider + 34 unit tests
providers/hdqwalls/          the second extension, same layout (21 tests)
providers/wallpapers4k/      the third extension, same layout (27 tests)
providers/alphacoders/       the fourth extension, same layout (29 tests)
providers/wallpapersafari/   the fifth extension, same layout (26 tests)
tools/build_repo_index.py    writes index.json from a directory of zips
.github/workflows/publish.yml  test → package → publish (gh-pages)
```

## The WallpaperCave extension

A faithful port of the CloudStream scraping architecture to the wallpaper
domain, in ~800 source lines (plus 560 lines of tests):

- **Two layers.** The provider is the catalog layer — popular feed, curated
  topics + category streams, search, details. The "extractor" layer is
  trivial by design: a wallpaper page `/w/{id}` resolves to its
  full-resolution original in the deterministic `/wp/{id}.*` (site) or
  `/uwp/{id}.*` (user upload) directories. Every grid thumbnail already
  discloses its original's path, so items carry a working download URL
  before details are ever fetched.
- **Regex mining, not a DOM library.** The payload ships only its own classes,
  so HTML is mined with hand-rolled patterns (attribute-order agnostic, both
  id families: numeric `wp14981887`-style and short alphanumeric `qq5qUZy`
  ones) and the load-more feed is parsed with `kotlinx.serialization`. This is
  exactly the CloudStream technique — small payloads, zero bundled parsers.
- **Site model.** wallpapercave.com is album-first: `/search?q=` answers with
  topic albums, never single wallpapers, so search runs the CloudStream
  two-step (albums → first few topic pages merged, 3 albums per page, capped
  at 10 pages). Popular is `/latest-uploads` + the one follow-up batch the
  site's `/morelatest` endpoint serves to a GET. Browse tabs walk the site's
  own `/categories/{slug}` album lists (curated topic first, then the stream,
  15 pages deep) — Girls, which the site files under no category of its own,
  rides its search listing as the stream — and avoid every
  robots-disallowed path in the process.
- **Politeness.** Fetches happen only on explicit user actions, sequentially,
  in small bounded batches with deep-pagination caps and a 60-second album
  cache — browser-equivalent traffic, never crawling. The site's upload rules
  are SFW-only and the provider declares `ContentRating.SFW` accordingly.
- **Verified.** 34 unit tests built from real captured markup (both id
  families, both attribute orders, category-stream pagination and its
  duplicate-skipping, query-preset routing and exact-term matching,
  interleaved-tab session isolation, degradation paths), ktlint-clean, and
  live-verified against the site via the gated `WallpaperCaveLiveCheckTest`
  (`WALLPAPERCAVE_LIVE=1`): latest feed, category streams through three
  pages with unique ids, the girls and cars feeds end to end, two-step
  search, and original-resolution details.

## The HDQWalls extension

The second scraper, purpose-built for a site that is *listing-first* — every
feed is the same shape, so the provider is one clean mapping (~640 source
lines plus 470 lines of tests):

- **One grid, every feed.** Popular (`/popular-wallpapers`), latest
  (`/latest-wallpapers`), every category (`/category/{slug}-wallpapers`)
  and search (`/search?q=`) serve the same 18-per-page grid with REAL
  pagination — no album indirection, no load-more seams, one request per
  page. The pagination bar's own `Next »` link is the "more exists"
  signal (absent on the last page — and past-the-end requests clamp to
  the last page on this site, so that link is the stopper, not a guess).
- **Fourteen shelves.** Popular and Latest ride the host's `sorting`
  vocabulary; Anime rides its `category` value (and Celebrities maps the
  host's `people` — the honest nearest expression); Girls, Cars,
  Superheroes, Games, Movies, Nature, Abstract, Animals, Bikes and Sports
  are tag-style `query` presets that ride the site's own search, which
  those terms address precisely (verified live: `cars` — 12,303 results,
  684 pages).
- **Real JPEG thumbnails.** The WallpaperCave AVIF trap does not exist
  here: `images.hdqwalls.com/wallpapers/bthumb/{file}.jpg` is a plain
  JPEG every Android decodes, and it discloses its multi-megabyte
  original by directory alone — the same filename under `/wallpapers/`.
- **No fake dimensions (fixed in 1.0.1).** The listing markup hard-codes
  `width='602' height='339'` — the site's uniform card crop — on every
  cell, true of no wallpaper, so 1.0.0 shipped items whose info sheet
  read "602x339" for everything. Grid items now carry no dimensions at
  all (the app renders "—"), and the true resolution stays where the
  site publishes it: the detail record.
- **True-resolution details.** Each wallpaper page publishes its
  `Original Resolution` (e.g. 3840x2159 — the only place true dims
  exist), an author credit, a download-size label and the site's own tag
  row; the parser reads all of them with two URL fallbacks
  (`data-original-url`, `og:image`) behind the blockquote.
- **Politeness.** robots.txt excludes only the autocomplete `/ajax?s=`
  endpoint (never called; suggestions come from seen tags) and
  `/addauthor`. Everything used is allowed, one request per page, capped
  at 100 pages deep.
- **Verified.** 21 unit tests from real captured markup (grid shapes,
  both quote styles, attribute orders, pagination stop signals, blank
  queries, deep-page caps, detail fallbacks) plus a gated
  `HdqWallsLiveCheckTest` (`HDQWALLS_LIVE=1`): popular through two fresh
  pages, the anime shelf, direct search pagination, a query-preset
  shelf, definitive details with true dimensions, and the random batch —
  all green against the live site.

## The 4K Wallpapers extension

The third scraper, built for a site that labels itself by resolution and
speaks schema.org in its markup (~700 source lines plus 690 lines of
tests):

- **A grid that introduces itself.** Every listing — the homepage's
  trending feed (the newest uploads, paginated 963 pages deep on `/`
  itself), the popular ranking, every category, search — serves the same
  twenty-four `wallpapers__item` cells, each an `ImageObject` carrying an
  `itemprop="keywords"` meta (title plus the site's own tags), an
  `itemprop="contentUrl"` link (the 800px preview) and the wallpaper-page
  anchor. The homepage's page one stacks a ten-item featured carousel on
  top — overlap-free, so it rides along as a bonus batch.
- **Originals disclosed by directory.** Previews live under
  `/images/walls/thumbs*/{id}.{ext}` and every one discloses its
  multi-megabyte source by filename alone under `/images/walls/orig/`
  (verified live: 5120x2880 JPEG at 5.8 MB, 4000x4000 PNG at 3.0 MB) —
  grid items carry a working download URL before details are ever
  fetched, PNG extensions included.
- **True-resolution details.** The site publishes every wallpaper in
  dozens of cropped sizes; the detail page's `Download Original (WxH)`
  link is the one labeled original, and the only place true dimensions
  exist. `details()` reads it (with a directory-disclosed fallback), so
  the app's v1.0.21 info sheet shows real specs like `5120x2880`.
- **No fake dimensions — ever.** The listing hard-codes its uniform
  400x225 card crop on every cell, the same trap hdqwalls 1.0.0 fell
  into; this provider shipped day one with null grid dimensions and the
  same regression guard in its live tests.
- **Fourteen shelves.** Popular and Latest ride the host's `sorting`
  vocabulary (Latest walks the homepage's freshest grid); Anime and
  People ride the host's `category` values — both native categories on
  this site; Nature, Space, Abstract, Cars, Games, Movies, Animals,
  Fantasy, Music and Dark are tag-style `query` presets riding the
  site's own search, which names them precisely (verified live:
  `nature` returns the Nature listing's own page one, 21 of 24).
- **The site's real search, page by page (1.1.0).** The website's search
  results scroll a hidden, script-driven pager — a "Load more" button
  requesting `/search/{query}?page=N` — and this provider rides exactly
  that stream: page one on the form's own `/search/?q=`, deeper pages on
  the load-more path form, `nextPage` gated by the pager's own
  `active`/`data-page` markers (verified live: `indian actress` walks 18
  distinct pages of twenty-four, the exact stream the browser scrolls).
  The same fix cured a silent 1.0.0 bug the single-word test suite had
  missed: the site reads its `?q=` as a raw percent-encoded token, so the
  `+` that URLEncoder produces served **zero results for every multi-word
  query** — `?q=indian%20actress` serves twenty-four, `?q=indian+actress`
  serves none, and the paged path answers `+` with a 404. Both encodings
  now travel as `%20`. The site's robots.txt excludes `/search/` for
  crawlers; this provider touches it only on explicit user actions — one
  request per page, byte-identical to the website's own traffic — never
  crawling or enumerating (a deliberate, disclosed exception; every other
  path stays inside the allowances).
- **Politeness.** robots.txt excludes only crawl-budget paths
  (`/search/`, `/recent/`, the thumb directories); the provider walks
  allowed pages exclusively — `/recent/` is deliberately unused because
  the homepage grid serves the identical freshest batch — and lets the
  app's image pipeline fetch exactly the URLs the site's own markup
  points every browser at. One request per page of twenty-four, capped
  at 100 pages. `/search/` carries the same disclosed robots exception
  the search bullet above documents.
- **Verified.** 27 unit tests from real captured markup (both cell
  shapes — full listing cells and lean related-cells, PNG extension
  round-trips, the ctrl-right pagination contract, the hidden search
  pager in live-cut and synthetic forms — page-one form, paged path form,
  `%20` encoding in both positions, last-page stop, zero-result and 404
  honesty, the deep cap — detail fallbacks, host-vocabulary routing, the
  no-dims regression guard) plus a gated `Wallpapers4KLiveCheckTest`
  (`WALLPAPERS4K_LIVE=1`): popular through two fresh pages, the
  multi-megabyte original itself, homepage and category pagination, real
  search matching its query through a fresh, non-repeating page two on
  the load-more walk, query-preset shelves, and definitive details
  including a portrait-shape guard — all green against the live site.

## The Alpha Coders extension

The fourth scraper, built for the deepest library in the set — Wallpaper
Abyss at alphacoders.com, hundreds of thousands of wallpapers behind
topic pages that speak schema.org with rare generosity (~660 source
lines plus 900 lines of tests):

- **The original file, disclosed in the grid.** Every listing cell is an
  `ImageObject` whose `contentUrl` points at the original itself —
  `images{N}.alphacoders.com/{shard}/{id}.{ext}`, a plain JPG or PNG the
  CDN serves directly (verified live: a 3840x2400 JPG at 1.4 MB fetched
  byte-for-byte, no hotlink protection). Grid items carry a working
  download URL before details are ever fetched, PNG extensions intact.
- **The site's real search, not a guess (1.1.0).** The site's own
  search box GETs `/search/view?q={query}&type=wallpaper`, answering the
  same fifteen-cell schema.org grid, paginated by `&page=N` — and this
  provider rides exactly that. 1.0.x approximated search by guessing
  topic addresses (`indian actress` → `/indian-actress-wallpapers`),
  which the site 301-redirected to the generic `indian` topic — cricket
  and landscapes, nothing like its own search results. A no-match query
  is the site's own empty grid (an honest miss), a page past the result
  set's end answers empty too (verified at page 999 — never clamped,
  never repeated), and the twelve tag shelves ride the same endpoint
  with plain search terms. The site's robots.txt excludes that path for
  crawlers; this provider touches it only on explicit user searches —
  one request per action, byte-identical to the site's own search box —
  never crawling or enumerating (a deliberate, disclosed exception;
  every other path stays inside the allowances).
- **Honest ends by construction.** The infinite-scroll listing carries
  no "more exists" signal, so a feed offers `nextPage` only when its page
  served items — and the end arrives as the site's own 404, mapped to an
  empty page that never advertises more. An empty 200 ends the feed too,
  and a hundred-page cap bounds the deepest scroll.
- **True dimensions, author, file size, colors — one request.** The
  big.php detail page publishes the file's TRUE resolution on its
  `main-content` image (the listing's 350x219 attrs are its uniform card
  crop, true of no wallpaper — the grid carries none, by regression
  guard), plus an author credit, a File Info box (`3840x2400 1.38 MB
  JPG`), the site's color row, and the caption. The info sheet fills
  from one request — honest nulls only where the page is silent.
- **The keyword row, minus the boilerplate.** Every cell carries the
  site's keyword row, which ends in the same five SEO tags on every
  single item (`desktop wallpaper, background, hd wallpaper, …`); the
  parser filters exactly that observed tail, so titles, tags and tag
  suggestions carry the site's own subject vocabulary — entities decoded
  (`Naruto & Sasuke`, not `Naruto &amp; Sasuke`).
- **Politeness.** One request per page of fifteen on explicit user
  actions, capped at a hundred pages; the one boundary probe at a feed's
  end is the same request a browser's next-page arrow produces. The site
  serves plain non-browser User-Agents without challenge (verified live
  against every path touched), and the image CDN serves the thumbnail and
  original exactly where the markup points every browser. The 250-KB
  listing pages parse inline on the caller's dispatcher — the same model
  as the other three scrapers — because the plugin ABI exposes no
  coroutine machinery to payload code (1.0.0 shipped a
  `withContext(Dispatchers.Default)` hop that no release APK could bind;
  1.0.1 parses inline, and the single-pass regex scan stays cheap while
  the host's HTTP facade keeps the network off the main thread).
- **Verified.** 29 unit tests from real captured markup (the full
  schema.org cell with its duplicated keywords and boilerplate tail,
  empty- and filled-name cells, flipped meta attribute order, malformed-
  cell isolation, the 404 boundaries that end feeds, the real search
  endpoint's routing and encoding, its hit mapping, its empty-grid miss
  and past-the-end shapes, its 404 defense and deep-cap guard, the
  detail record's dims/author/size/colors, the by-author title form, PNG
  extensions, KB sizes, the no-dims regression guard, host-vocabulary
  routing, the twelve search-ridden shelves) plus a gated
  `AlphaCodersLiveCheckTest` (`ALPHACODERS_LIVE=1`): the popular batch
  through a fresh page two, the newest and anime feeds, real search
  matching its query with a non-repeating page two and an honest garbage
  miss, details with true dimensions and file size, the original and
  thumbnail really serving, and a PNG end-to-end — all green against the
  live site.

## The WallpaperSafari extension

The fifth scraper, built for a site that is *gallery-first* — content
lives in topic galleries, each served complete on one server-rendered
page (~700 source lines plus 590 lines of tests):

- **Two grid shapes, one parser.** The home page's "Popular wallpapers"
  wall serves fifty self-contained `gallery-item` anchors (the like
  widget inside), while every topic gallery serves its complete set as
  `post-image` blocks (the like widget in a sibling `imginfo` div — the
  reason those cells slice by their wrapping div, not by the anchor).
  Both shapes carry the same payload: the medium card image, the CDN
  path of the original, the uploader, and — uniquely in this repository
  — the file's TRUE dimensions, published by the like widget itself
  (`data-width`/`data-height`), so listing items arrive with real specs
  like `2560x1440` before details are ever fetched.
- **Originals disclosed by CDN path.** `mcdn.wallpapersafari.com/medium/
  {a}/{b}/{id}.{ext}` is the card; the original is the same path on
  `cdn.wallpapersafari.com/{a}/{b}/{id}.{ext}` — one directory swap,
  extension preserved (verified live: a 279-KB 2560x1440 JPEG and a
  PNG, both serving). The home wall's generic `1920x1080 wallpaper`
  alt texts are recognized as the labels they are and yield no title —
  honest emptiness over noise.
- **The site's real search, album-first.** wallpapersafari.com's search
  box is a plain GET form to `/search?q=`, and it answers with GALLERY
  cards, never single wallpapers — so search runs the CloudStream
  two-step the WallpaperCave extension established: the query's gallery
  cards, then the batch's topic pages fetched one by one and merged,
  three galleries per app page, deduplicated, ten pages deep at most.
  One query matching a single gallery serves that gallery whole —
  `indian actress` delivers the site's 89-image gallery in one page,
  exactly what the website itself shows. A query that matches nothing
  is read from the site's own `couldn't find anything` heading: the
  trending suggestions that follow it are never mistaken for results.
  Both `+`- and `%20`-encoded multi-word queries serve identically
  (verified live — the encoding trap that wallpapers4k 1.0.0 shipped
  does not exist here).
- **Category directories as streams — and the 1.0.1 tab lesson.** The
  site's `/category/{slug}/` pages are directories of the same gallery
  cards (168 for art). Only one of them is reachable through the host's
  fixed category vocabulary (`general`/`anime`/`people`): the host's
  `anime` filter walks `/category/art/anime/` three galleries per page
  with served-id dedupe, the same stream machinery as search (the site
  files no people category, so `people` honestly lands on the default
  popular wall). 1.0.0 declared the other six shelves — Art, Animals,
  Cars, Nature, Sports, Travel — as `category` values of their own,
  which the host's section-to-query mapping silently drops (its
  vocabulary is fixed); every one of those tabs collapsed to the
  default popular wall and showed identical wallpapers. 1.0.1 moves
  them onto the host's `query` presets — the mechanism every other
  provider here already uses — so each tab routes through the
  provider's real search and serves the site's own answer for its term
  (57 galleries for *art*, 52 for *animals*, 60 each for *cars* and
  *nature*, 50 for *sports*, 24 for *travel*, all verified live), and
  a regression test now fails on any `category` value the host
  vocabulary cannot translate.
- **No fake feeds.** `/latest-uploads/` is an empty JavaScript shell —
  no server-rendered latest exists — and no random endpoint exists, so
  LATEST and RANDOM are deliberately not declared. The home wall is
  one honest page: fifty complete items, no fabricated second page.
- **Politeness.** robots.txt excludes `/download/`, `/downloadres/`
  (never touched — the cards disclose the originals directly),
  `/cdn-cgi/` and `/search`; search carries the same deliberate,
  disclosed exception wallpapercave and 4kwallpapers already document —
  touched only on explicit user actions, one request per action plus
  one per merged gallery, byte-identical to the site's own search box,
  never crawling or enumerating. The `/ajax/` endpoints the site
  reserves for liking, rating and load-more are never called. Every
  other path this provider touches stays inside the allowances.
- **Verified.** 26 unit tests from real captured markup (both grid
  shapes — home cells and post-image blocks with their sibling like
  widgets — reordered single-quoted attributes, debris-cell isolation,
  the gallery cards with their hit/miss headings, the two-step merge
  with batching, dedupe and skip-on-failure, the zero-result marker's
  trending defense, the deep cap, category streams and their served-id
  windows, the unmapped-category fallback, the detail record, URL
  round-trips, tag suggestions, sections and capabilities, and the
  host-vocabulary guard that keeps every section on a key the host can
  translate) plus a gated
  `WallpaperSafariLiveCheckTest` (`WALLPAPERSAFARI_LIVE=1`): the home
  wall with true dimensions, real search matching one gallery whole,
  many-gallery search walking a fresh page two, the honest zero-result
  miss, the anime category stream, the six category shelves each
  serving its own distinct feed, definitive details, and the
  disclosed original really serving — all green against the live site.

## Publishing (CI)

On every push to `main` that touches `providers/**`, `tools/**`, or the
workflow itself, [publish.yml](.github/workflows/publish.yml):

1. checks out this repo (extension sources) and the
   [Cloud-Wallpaper](https://github.com/alamsamir7666-ux/Cloud-Wallpaper)
   app repo (build scaffolding: Gradle, `build-logic`, `provider/api`),
2. overlays every `providers/*` module from this repo onto the app tree and
   registers it in `settings.gradle.kts` if needed,
3. runs `check` (unit tests + ktlint) and `packageExtension` (d8 dex + zip)
   for each module,
4. builds `index.json` and publishes everything to `gh-pages` as an orphan
   commit.

`workflow_dispatch` is enabled for manual republishes.

## Building locally

You need JDK 17 and an Android SDK (any platform ≥ 34 works; only `android.jar`
is used, by `d8`):

```bash
git clone https://github.com/alamsamir7666-ux/Cloud-Wallpaper app
cd app
git clone https://github.com/alamsamir7666-ux/WallpaperExtensions ../extensions

for provider in ../extensions/providers/*/; do
  name="$(basename "$provider")"
  rm -rf "providers/$name" && cp -r "$provider" "providers/$name"
  grep -q "\":providers:$name\"" settings.gradle.kts \
    || echo "include(\":providers:$name\")" >> settings.gradle.kts
done

./gradlew :providers:wallpapercave:check :providers:wallpapercave:packageExtension
# → providers/wallpapercave/build/outputs/extension/cloudimage.wallpapercave.zip
./gradlew :providers:hdqwalls:check :providers:hdqwalls:packageExtension
# → providers/hdqwalls/build/outputs/extension/cloudimage.hdqwalls.zip
./gradlew :providers:wallpapers4k:check :providers:wallpapers4k:packageExtension
# → providers/wallpapers4k/build/outputs/extension/cloudimage.wallpapers4k.zip
./gradlew :providers:alphacoders:check :providers:alphacoders:packageExtension
# → providers/alphacoders/build/outputs/extension/cloudimage.alphacoders.zip
./gradlew :providers:wallpapersafari:check :providers:wallpapersafari:packageExtension
# → providers/wallpapersafari/build/outputs/extension/cloudimage.wallpapersafari.zip
```

## Adding another extension

Create `providers/<name>/` with an `extension.json` (non-blank `id`, an
`entryClass` implementing `WallpaperProvider`) and a `build.gradle.kts` that
applies `cloudimage.provider`. CI picks up every module under `providers/*`
automatically — no workflow edits needed.

## License

Apache 2.0, like the app. Wallpapers belong to their creators; this repository
contains only code that reads a public website on explicit user actions.

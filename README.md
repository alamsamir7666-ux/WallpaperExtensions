# WallpaperExtensions

Extension repository for the [Cloudimage](https://github.com/alamsamir7666-ux/Cloud-Wallpaper)
Android app — installable wallpaper-source packages, published to this repo's
`gh-pages` branch.

The repository ships four keyless scrapers, all modeled on the two-layer
Provider/Extractor pattern used by CloudStream plugins: the star,
**`cloudimage.wallpapercave`**, its sharp-eyed sibling
**`cloudimage.hdqwalls`** — HD, 4K, 5K and 8K wallpapers from hdqwalls.com,
the friendliest scraping target in the set (real pagination everywhere,
direct search results, plain JPEG thumbnails) —
**`cloudimage.wallpapers4k`** — the resolution-first library at
4kwallpapers.com, whose schema.org markup hands over title, tags and
originals with unusual candor — and **`cloudimage.wallpaperaccess`** —
the collection library at wallpaperaccess.com, whose listings publish
their files' TRUE dimensions right in the grid, verified pixel-exact.

## Install in the app

1. Open **Cloudimage → Extensions → Add repository**
2. Paste this repo's URL:
   `https://github.com/alamsamir7666-ux/WallpaperExtensions`
3. The app resolves it to the published index
   (`raw.githubusercontent.com/…/WallpaperExtensions/gh-pages/index.json`),
   verifies every package's SHA-256 on download, and installs it as a
   runtime-loaded provider.
4. **Install** → *WallpaperCave*, *HDQWalls*, *4K Wallpapers*,
   *WallpaperAccess*, or any mix. No API key, no account — each reads its
   site the way the site's own browser UI does.

## Packages

| Package | Version | Size | Capabilities | Notes |
|---|---|---|---|---|
| `cloudimage.wallpapercave` | 1.2.0 | 24 KB | popular, latest, search, filters, tags | Keyless scraper, SFW, API v1 |
| `cloudimage.hdqwalls` | 1.0.1 | 18 KB | popular, latest, search, filters, tags, random | Keyless scraper, SFW, API v1 |
| `cloudimage.wallpapers4k` | 1.0.0 | 17 KB | popular, latest, search, filters, tags | Keyless scraper, SFW, API v1 |
| `cloudimage.wallpaperaccess` | 1.0.0 | 15 KB | popular, latest, search, filters, tags | Keyless scraper, SFW, API v1 |

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
providers/wallpapers4k/      the third extension, same layout (19 tests)
providers/wallpaperaccess/   the fourth extension, same layout (25 tests)
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
- **Honest search.** The site's search serves a single page of
  twenty-four with no pagination — `nextPage` is null, and deeper pages
  answer empty instead of repeating the batch.
- **Politeness.** robots.txt excludes only crawl-budget paths
  (`/search/`, `/recent/`, the thumb directories); the provider walks
  allowed pages exclusively — `/recent/` is deliberately unused because
  the homepage grid serves the identical freshest batch — touches
  `/search/` only on an explicit user search, and lets the app's image
  pipeline fetch exactly the URLs the site's own markup points every
  browser at. One request per page of twenty-four, capped at 100 pages.
- **Verified.** 19 unit tests from real captured markup (both cell
  shapes — full listing cells and lean related-cells, PNG extension
  round-trips, the ctrl-right pagination contract, single-page search
  honesty, detail fallbacks, host-vocabulary routing, the no-dims
  regression guard) plus a gated `Wallpapers4KLiveCheckTest`
  (`WALLPAPERS4K_LIVE=1`): popular through two fresh pages, the
  multi-megabyte original itself, homepage and category pagination,
  single-page search, query-preset shelves, and definitive details
  including a portrait-shape guard — all green against the live site.

## The WallpaperAccess extension

The fourth scraper, built for a collection-first site with a rare gift:
its listings publish each file's TRUE dimensions right in the grid
(~460 source lines plus 600 lines of tests):

- **True dimensions, zero requests.** Every wallpaper cell carries
  `data-or="3840x2160"` — the file's own dimensions — verified
  pixel-exact against the served JPEG/PNG bytes (the live test suite
  sniffs the image header itself). No card-crop trap, no details()
  round-trip: the info sheet shows real resolutions from the listing
  alone, portrait stays portrait (736x1389 parses exactly that), and
  the host's v1.0.21 viewer never even needs to ask.
- **One page, whole collection.** The site paginates NOTHING —
  `/most-popular`, `/new` and every collection (`/fall`, `/naruto`,
  `/4k-gaming`, …) serves its entire inventory, twenty to a hundred
  items, in one server-rendered page. Every feed answers with its
  single batch and `nextPage` null; deeper pages answer honestly empty
  without a request.
- **Previews and originals by directory.** Cells disclose
  `data-fullimg="/full/{id}.{ext}"` — the original file served directly
  — and the same filename under `/thumb/` is the site's lighter
  600-pixel preview (verified fifteen-for-fifteen across six listings,
  both extensions).
- **Robots-compliant search.** The site's robots.txt excludes
  `/search`, so search never touches it: the query is slugified into
  the site's own collection address shape (`naruto` → `/naruto`,
  `4K Gaming!` → `/4k-gaming`) and that page is walked. An exact
  collection hit serves its whole batch; a 404 is a miss — an honest
  empty results page, not a failure.
- **Sixteen shelves.** Popular and Latest ride the host's `sorting`
  vocabulary (Latest walks the site's `/new` feed); Anime and People
  ride the host's `category` values — both real collections here; and
  Nature, Space, Abstract, Cars, Games, Movies, Animals, Fantasy,
  Music, Dark, Minimal and City are tag-style `query` presets naming
  real collections (all verified live, 27–104 items each).
- **Details re-walk the listing.** The id IS the re-fetch address,
  `collection/fileName`, so `details()` re-serves the cell's own record
  — original, true dimensions, alt-derived title, owning collection as
  tag — with the site's own share URL (`collection#id`) as the source
  link. No author, no file size: honest nulls, never inventions.
- **Verified.** 25 unit tests from real captured markup (both img
  flavors — `src` and lazy `data-src`, attribute-order shuffles, the
  newline-spanning `data-download` value, malformed-cell isolation,
  404 and homepage-card shapes, entity-escaped titles, duplicate-cell
  insurance, slugify) plus a gated `WallpaperAccessLiveCheckTest`
  (`WALLPAPERACCESS_LIVE=1`): the popular batch with dimensions on
  every item, `data-or` against the file's own pixels, the lighter
  thumb, fresh and category listings, slug-guess hit and honest miss,
  details round-trip, and a portrait-shape guard — all green against
  the live site.

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
./gradlew :providers:wallpaperaccess:check :providers:wallpaperaccess:packageExtension
# → providers/wallpaperaccess/build/outputs/extension/cloudimage.wallpaperaccess.zip
```

## Adding another extension

Create `providers/<name>/` with an `extension.json` (non-blank `id`, an
`entryClass` implementing `WallpaperProvider`) and a `build.gradle.kts` that
applies `cloudimage.provider`. CI picks up every module under `providers/*`
automatically — no workflow edits needed.

## License

Apache 2.0, like the app. Wallpapers belong to their creators; this repository
contains only code that reads a public website on explicit user actions.

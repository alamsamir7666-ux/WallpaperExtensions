# WallpaperExtensions

Extension repository for the [Cloudimage](https://github.com/alamsamir7666-ux/Cloud-Wallpaper)
Android app — installable wallpaper-source packages, published to this repo's
`gh-pages` branch.

The star of this repository is **`cloudimage.wallpapercave`**: a keyless
WallpaperCave scraper — the first scraping source in the ecosystem, modeled
on the two-layer Provider/Extractor pattern used by CloudStream plugins.

## Install in the app

1. Open **Cloudimage → Extensions → Add repository**
2. Paste this repo's URL:
   `https://github.com/alamsamir7666-ux/WallpaperExtensions`
3. The app resolves it to the published index
   (`raw.githubusercontent.com/…/WallpaperExtensions/gh-pages/index.json`),
   verifies every package's SHA-256 on download, and installs it as a
   runtime-loaded provider.
4. **Install** → *WallpaperCave*. No API key, no account — it reads
   wallpapercave.com the way the site's own browser UI does.

## Packages

| Package | Version | Size | Capabilities | Notes |
|---|---|---|---|---|
| `cloudimage.wallpapercave` | 1.1.0 | 23 KB | popular, latest, search, filters, tags | Keyless scraper, SFW, API v1 |

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
└── src/…                    parser + provider + 23 unit tests
tools/build_repo_index.py    writes index.json from a directory of zips
.github/workflows/publish.yml  test → package → publish (gh-pages)
```

## The WallpaperCave extension

A faithful port of the CloudStream scraping architecture to the wallpaper
domain, in ~720 source lines (plus 490 lines of tests):

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
  site's `/morelatest` endpoint serves to a GET. Categories walk the site's
  own `/categories/{slug}` album lists (curated topic first, then the stream,
  15 pages deep) — and avoid every robots-disallowed path in the process.
- **Politeness.** Fetches happen only on explicit user actions, sequentially,
  in small bounded batches with deep-pagination caps and a 60-second album
  cache — browser-equivalent traffic, never crawling. The site's upload rules
  are SFW-only and the provider declares `ContentRating.SFW` accordingly.
- **Verified.** 30 unit tests built from real captured markup (both id
  families, both attribute orders, category-stream pagination and its
  duplicate-skipping, degradation paths), ktlint-clean, and live-verified
  against the site via the gated `WallpaperCaveLiveCheckTest`
  (`WALLPAPERCAVE_LIVE=1`): latest feed, category streams through three
  pages with unique ids, two-step search, and original-resolution details.

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
```

## Adding another extension

Create `providers/<name>/` with an `extension.json` (non-blank `id`, an
`entryClass` implementing `WallpaperProvider`) and a `build.gradle.kts` that
applies `cloudimage.provider`. CI picks up every module under `providers/*`
automatically — no workflow edits needed.

## License

Apache 2.0, like the app. Wallpapers belong to their creators; this repository
contains only code that reads a public website on explicit user actions.

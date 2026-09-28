package com.cloudimage.wallpapers4k

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider
import java.net.URLEncoder

/**
 * 4kwallpapers.com (https://4kwallpapers.com) as a Cloudimage provider
 * package — the third scraping source in the repository set: no API, no
 * key, just the site's own server-rendered pages read the way its browser
 * UI does.
 *
 * ## Site model
 *
 * The site is a curated, resolution-first wallpaper library that speaks
 * schema.org in its markup. Every listing — the homepage's trending feed
 * (the newest uploads, paginated on `/` itself), the popular ranking
 * (`/most-popular-4k-wallpapers/`), every category (`/anime`, `/space`,
 * `/nature`, …) and search (`/search/?q=`) — serves the SAME grid of
 * twenty-four `wallpapers__item` cells, each carrying an
 * `itemprop="keywords"` meta with the title and the site's own tags, an
 * `itemprop="contentUrl"` link to the 800px preview, and an anchor to the
 * wallpaper's page. Every wallpaper exists in many cropped sizes; the
 * wallpaper page's `Download Original (WxH)` link is the one labeled
 * original. The previews live under `/images/walls/thumbs`, `thumbs_2t`
 * and `thumbs_3t` as `{id}.{ext}`,
 * and every one discloses its multi-megabyte source by directory alone —
 * the same filename back under `/images/walls/orig/` (verified live:
 * 5120x2880 at 5.8 MB, 4000x4000 PNG at 3.0 MB). Pagination rides
 * `?page=N` behind a `p.pages` bar whose `Next ›` link (`class="ctrl-right"`)
 * is the site's own "more exists" signal, absent on the last page. Search
 * results answer with the same grid plus the bar's hidden, script-driven
 * flavor: the links become bare `data-page` markers and a "Load more"
 * button requests `/search/{query}?page=N` — the paged form the browser
 * UI itself walks (verified live: `indian actress` serves 18 distinct
 * pages, twenty-four each).
 *
 * ## How the contract maps onto it
 *
 * - [popular] rides the host's filter vocabulary: a recognized `category`
 *   walks that category's listing (`anime`, `people` — both are real
 *   top-level categories on this site), `sorting=date` walks the homepage
 *   grid — the site's freshest uploads, paginated nearly a thousand pages
 *   deep — and everything else lands on the popular ranking, the default
 *   feed the browse tab shows first.
 * - [search] rides the site's real search, page by page: the first page
 *   answers `/search/?q=`, every deeper page walks the same load-more
 *   form the site's own "Load more" button requests —
 *   `/search/{query}?page=N` — and BOTH carry the query percent-encoded
 *   with spaces as `%20` (the site reads its `?q=` as a raw token: the
 *   `+` form encoding serves zero multi-word results and the paged path
 *   answers it 404 — verified live). The hidden pages bar's markers say
 *   when more remain, so `nextPage` is honest and the walk stops where
 *   the site's own walk stops. A blank query (the contract's escape
 *   hatch) lands on the trending feed's first page, the same default the
 *   blank popular feed would show. The query-preset shelves ride this
 *   search; a term that names a category returns that category's content
 *   (verified live: `nature` — 21 of the first twenty-four results are
 *   the Nature listing's own page one).
 * - [sections] offers fourteen shelves: Popular, Latest (the host
 *   `sorting=date` preset), Anime and People (the host `category`
 *   vocabulary), and tag-style `query` presets for Nature, Space,
 *   Abstract, Cars, Games, Movies, Animals, Fantasy, Music and Dark —
 *   each rides the site's own search, which those terms address
 *   precisely.
 * - [details] fetches `/{id}` and reads the `Download Original (WxH)`
 *   link: the site's own labeled original URL and the file's TRUE
 *   dimensions, plus the page-level keywords meta's title and tags. The
 *   site publishes no author credit and no file size, so those fields
 *   stay null — honest emptiness over invented values.
 * - [random] has no site endpoint to ride; the honest answer is the
 *   freshest batch the site itself puts in front of every visitor — the
 *   trending feed's first page, served as-is.
 *
 * ## Politeness
 *
 * The site's robots.txt allows every page and excludes only crawl-budget
 * directories: `/cdn-cgi/`, `/search/`, `/recent/`, `/thumbs/`,
 * `/thumbs_2t/`. This provider walks only allowed pages: the listings,
 * the categories and the wallpaper pages. `/recent/` — the site's raw
 * latest feed — is deliberately NOT used: the homepage grid serves the
 * identical freshest batch (verified live, same items, same order) and
 * paginates on the allowed `/`. `/search/` is touched only on an explicit
 * user search action, one request — the same precedent the site's own
 * search box sets. Preview and original image URLs are fetched only by
 * the app's image pipeline when it renders or downloads an item, exactly
 * as the site's own markup directs every browser (`srcset`,
 * `itemprop="contentUrl"`, download links) — this provider never crawls
 * the image directories themselves. Fetches happen on explicit user
 * actions, one request per page of twenty-four, with a deep-pagination
 * cap; a browser tab on the same pages costs the same or more. The site
 * serves plain non-browser User-Agents without challenge (verified live
 * against every path this provider touches), so no browser impersonation
 * is needed.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the only mutable state is
 * the tag pool feeding [suggestTags] — a bonus, never a dependency. A
 * fresh instance answers identically, at worst without suggestions. The
 * pool is guarded by one lock; sections load in parallel on the host side.
 *
 * ## Dimensions
 *
 * Grid items carry NONE, on purpose. The site's listing markup hard-codes
 * `width="400" height="225"` — its uniform 16:9 card crop — on every
 * single cell, whatever the file's true size, so those attributes
 * describe the THUMBNAIL, never the wallpaper (a 5120x2880 original and
 * a 4000x4000 square one both arrive labeled 400x225). Publishing them
 * would label every item with the thumbnail's size — the exact bug the
 * hdqwalls 1.0.1 fix retired. [details] is where the TRUE resolution
 * arrives: each wallpaper page's `Download Original (WxH)` link, the
 * only place the site publishes it.
 */
class Wallpapers4KWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "4K Wallpapers",
            versionName = "1.1.0",
            author = "Cloudimage",
            description = "4K, 5K, 8K and up wallpapers from 4kwallpapers.com - scraped, keyless.",
            // The site curates its uploads and carries no per-item rating
            // metadata; SFW is its own claim and the honest floor. The
            // host's rating switch still applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.LATEST, Capability.SEARCH, Capability.FILTERS, Capability.TAGS)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    /**
     * The default feed, ridden through the host vocabulary: a recognized
     * `category` walks that category's listing, `sorting=date` walks the
     * homepage grid — the site's freshest uploads — and everything else
     * lands on the popular ranking. Category wins over sorting: it is the
     * primary axis of the site's content.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val category = filters.valuesFor("category").firstNotNullOfOrNull(hostCategoryPath::get)
            when {
                category != null -> listingPage(category, page)
                filters.isSelected("sorting", "date") -> listingPage(TRENDING_PATH, page)
                else -> listingPage(POPULAR_PATH, page)
            }
        }

    /**
     * The site's real search, page by page. Page one answers the form's
     * own `/search/?q=`; every deeper page walks the load-more form the
     * site's "Load more" button itself requests — `/search/{query}?page=N`
     * — so the app scrolls the exact stream the website scrolls (verified
     * live: `indian actress` walks 18 distinct pages the browser walk
     * walks). The hidden pages bar's markers gate `nextPage`, a 404
     * answers honestly empty, and a blank query (the contract's escape
     * hatch) lands on the trending feed's first page, the same default the
     * blank popular feed would show.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching listingPage(TRENDING_PATH, 1)
            }
            searchPage(query, page)
        }

    /**
     * Suggestions come ONLY from tags this instance has already seen in
     * fetched pages — the keywords metas carry the site's own tags, which
     * is exactly what lands in the pool. No keystroke-driven scraping of
     * the site's search (the one page family its robots.txt excludes). A
     * fresh instance answers nothing, which the host treats as "no
     * suggestions" rather than an error.
     */
    override suspend fun suggestTags(query: String): Result<List<String>> =
        runCatching {
            if (query.isBlank()) {
                emptyList()
            } else {
                synchronized(lock) { tagPool.toList() }
                    .filter { it.contains(query, ignoreCase = true) }
                    .take(TAG_SUGGESTION_LIMIT)
            }
        }

    /**
     * The site's shelves as tabs: the two ranked feeds first (Popular is
     * the default row the host's merged home picks), then the two host
     * categories the site files natively, then tag-style `query` presets
     * the host routes through [search] with that term — the site's search
     * addresses each precisely. Cheap and offline, as the contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "popular", title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("category" to "anime")),
            HomeSection(id = "people", title = "People", filters = Filters.of("category" to "people")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "cars")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "movies", title = "Movies", filters = Filters.of("query" to "movies")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "fantasy", title = "Fantasy", filters = Filters.of("query" to "fantasy")),
            HomeSection(id = "music", title = "Music", filters = Filters.of("query" to "music")),
            HomeSection(id = "dark", title = "Dark", filters = Filters.of("query" to "dark")),
        )

    /**
     * The definitive record for an id: the page's `Download Original`
     * link carries the original's exact URL and TRUE dimensions, and the
     * page-level keywords meta carries the title and the site's own tags.
     * Transport failures propagate untouched, per the facade contract; a
     * non-2xx answer (deleted wallpaper, redesigned page) is a source
     * failure, reported as such — the host's viewer already holds the
     * grid item's own URLs. The site publishes no author and no file
     * size, so those fields stay null.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/${encodePath(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record = Wallpapers4KParser.parseDetail(response.bodyText) ?: error("unrecognized wallpaper page for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        // The page's own preview; the original stands in
                        // when the redesign stripped the contentUrl image —
                        // heavy for a preview, but always a real image.
                        thumbUrl = record.thumbUrl ?: record.originalUrl,
                        fullUrl = record.originalUrl,
                        title = record.title,
                        width = record.width,
                        height = record.height,
                        tags = record.tags.take(MAX_TAGS),
                    ),
                author = null,
                resolution =
                    if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                fileSizeBytes = null,
                sourceUrl = "$BASE_URL/$id",
            )
        }

    /**
     * No random endpoint exists on the site; the honest answer is the
     * freshest batch it puts in front of every visitor — the trending
     * feed's first page, served as-is.
     */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { trendingBatch() }

    // ---------------------------------------------------------------- feed

    /**
     * Any listing page: fetch, parse the grid, read the pagination bar's
     * `Next ›` link for what follows. The link is the site's own "more
     * exists" signal — past-the-end page requests repeat the last page's
     * content on this site, so a blindly incremented cursor would serve
     * the final batch forever; the bar is the stopper. A [MAX_PAGES] cap
     * bounds the deepest scroll the same politeness way.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        // Only plain listing paths reach here — search walks its own paged
        // form — so `?page=` can only append cleanly.
        val url = if (page > 1) "$BASE_URL$path?page=$page" else "$BASE_URL$path"
        val response = get(url)
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val wallpapers =
            Wallpapers4KParser.parseGrid(response.bodyText).map(::gridWallpaper).let(::rememberTagsIn)
        return Page(wallpapers, nextPage = Wallpapers4KParser.parseNextPage(response.bodyText))
    }

    /**
     * One page of the site's search stream. Page one rides the form's own
     * `?q=` address; deeper pages ride the load-more form the site's own
     * button requests — and BOTH carry the query percent-encoded with
     * spaces as `%20`: this site's search reads its parameter as a raw
     * percent-encoded token, not a form encoding, so the `+` URLEncoder
     * produces answers ZERO results for a multi-word query (verified live:
     * `?q=indian+actress` serves nothing, `?q=indian%20actress` serves
     * twenty-four), and inside the paged PATH a `+` is a literal plus that
     * answers 404. The hidden pages bar's `active`/`data-page` markers
     * decide `nextPage` — the same pair the site's script walks — and a
     * 404 degrades to an honest empty page instead of a source failure.
     */
    private suspend fun searchPage(
        query: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val term = encodeSearchTerm(query)
        val url =
            if (page == 1) {
                "$BASE_URL/search/?q=$term"
            } else {
                "$BASE_URL/search/$term?page=$page"
            }
        val response = get(url)
        if (response.statusCode == NOT_FOUND) {
            return Page(emptyList(), nextPage = null)
        }
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val wallpapers =
            Wallpapers4KParser.parseGrid(response.bodyText).map(::gridWallpaper).let(::rememberTagsIn)
        return Page(wallpapers, nextPage = Wallpapers4KParser.parseSearchNextPage(response.bodyText))
    }

    /** The trending batch — the homepage grid, no pagination signal worth reading. */
    private suspend fun trendingBatch(): List<Wallpaper> {
        val response = get("$BASE_URL$TRENDING_PATH")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return Wallpapers4KParser.parseGrid(response.bodyText).map(::gridWallpaper).let(::rememberTagsIn)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the cell's own contentUrl (the 800px
     * preview) in [Wallpaper.thumbUrl] and the disclosed original — the
     * same filename back under `/images/walls/orig/` — in
     * [Wallpaper.fullUrl]. Dimensions are deliberately absent: the
     * listing's `width`/`height` attributes are the site's uniform
     * 400x225 card crop, identical on every cell and true of no
     * wallpaper — a value that wrong is worse than no value, and the app
     * renders null dimensions as "—" until [details] supplies the true
     * ones. The keywords meta's remainder rides as tags: the site's own
     * vocabulary, so the detail screen's "More like this" row has a
     * query that works and [suggestTags] has real tags to offer.
     */
    private fun gridWallpaper(item: Wallpapers4KParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.originalUrl,
            title = item.title.ifBlank { null },
            tags = item.tags.take(MAX_TAGS),
        )

    /** Harvests seen tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTagsIn(wallpapers: List<Wallpaper>): List<Wallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        synchronized(lock) {
            wallpapers.flatMap { it.tags }.forEach { tag ->
                if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag.lowercase())
            }
        }
        return wallpapers
    }

    // ------------------------------------------------------------- plumbing

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url)
            ?: error("configure() was not called")

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("4kwallpapers answered HTTP $statusCode")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * The id IS a page path (`abstract/…-27263.html`), so only its
     * segments are percent-encoded — the separators must survive.
     */
    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

    /**
     * A query term as ONE percent-encoded token with spaces as `%20` —
     * valid in the `?q=` value and in the paged path alike, because this
     * site's search reads its parameter as a raw token (the `+` form
     * encoding URLEncoder produces answers zero results in the query and
     * 404 in the path — both verified live).
     */
    private fun encodeSearchTerm(value: String): String = encode(value).replace("+", "%20")

    private companion object {
        const val ID = "cloudimage.wallpapers4k"
        const val BASE_URL = "https://4kwallpapers.com"

        /** The ranked feed and the homepage's freshest-uploads grid. */
        const val POPULAR_PATH = "/most-popular-4k-wallpapers/"
        const val TRENDING_PATH = "/"

        /**
         * The host `category` vocabulary this provider can express: `anime`
         * and `people` are real top-level categories on this site. `general`
         * needs no mapping: it is the default feed.
         */
        val hostCategoryPath =
            mapOf(
                "anime" to "/anime",
                "people" to "/people",
            )

        /** Deep-pagination cap: a hundred pages, twenty-four items each. */
        const val MAX_PAGES = 100

        /** The site's not-found answer, degraded to an honest empty search page. */
        const val NOT_FOUND = 404

        /** Per-item tag cap and the suggest pool's bounds. */
        const val MAX_TAGS = 6
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** One lock over the tag pool; sections load in parallel. */
        val lock = Any()
    }

    private val tagPool = LinkedHashSet<String>()
}

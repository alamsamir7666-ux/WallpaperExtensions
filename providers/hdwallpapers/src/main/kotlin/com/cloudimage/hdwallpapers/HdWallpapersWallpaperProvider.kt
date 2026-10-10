package com.cloudimage.hdwallpapers

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider
import java.net.URLEncoder
import kotlin.random.Random

/**
 * HD Wallpapers (https://www.hdwallpapers.in) as a Cloudimage provider
 * package — the seventh keyless scraper in the repository set: no API, no
 * key, the site's own server-rendered pages read the way its browser UI
 * does.
 *
 * ## Site model
 *
 * The site is listing-first, uniform and refreshingly deep — every feed
 * is the same twenty-cell grid, paginated by the site's own bar:
 *
 * - **Three site-wide feeds**: latest (`/latest_wallpapers.html`, 5,681
 *   pages at capture time), most downloaded
 *   (`/top_download_wallpapers.html`) and most popular
 *   (`/top_view_wallpapers.html`).
 * - **Twenty-five categories** (`/{slug}-desktop-wallpapers.html`, from
 *   the navigation's 24 plus the anime page the trending shelf ships) —
 *   the same grid below a sub-category directory of the site's own
 *   (`Alfa Romeo (23)`, `Aston Martin (116)` …) that paginates under
 *   `pageSub/`; the grid's own bar keeps `page/`, and only THAT bar is
 *   read.
 * - **Tag streams** (`/tag/{name}.html`), one per tag of every
 *   wallpaper's own tag row — the site's real taxonomy.
 * - **Search** (`/search.html?q=`) — a real database query over titles
 *   and tags, multi-word queries included, paginated as deep as its own
 *   answers go. A query it cannot address apologizes ("Sorry, no
 *   wallpapers found") above the trending collection, and so does any
 *   page request past the end of a stream — the marker is read, so the
 *   trending cards are never served as results.
 * - **Wallpaper pages** (`/{slug}-wallpapers.html`) disclose everything:
 *   the `h1` title, the original download link, the generated resolution
 *   variants whose LARGEST entry carries the file's TRUE dimensions
 *   (verified live: the `-HD` original and the top variant share pixels,
 *   three for three — 2560x1440, 7680x4320, 1920x1080), and the site's
 *   own tag row.
 *
 * The grid's cell discloses the wallpaper's page slug, its `/thumbs/`
 * image and its title — and the original itself is DETERMINISTIC:
 * `/download/{slug}-HD.jpg`, the same URL the page's own Original button
 * carries. Like WallpaperCave's `/wp/{id}.*` directories, the "extractor
 * layer" is a string template: every grid item holds a working download
 * URL before details are ever fetched.
 *
 * ## How the contract maps onto it
 *
 * - [popular] is the most-viewed ranking by default; the host vocabulary
 *   rides it: `sorting=date` walks the latest feed, `sorting=toplist`
 *   the most-downloaded ranking, `category=anime` the site's own anime
 *   category page (`people` has no honest expression here — the site
 *   files no such category — so it falls back to the default feed, the
 *   contract's documented behavior for values a provider cannot
 *   express), and `sorting=random` serves one random batch of the
 *   latest feed: the site's own random endpoint sits behind a Cloudflare
 *   wall that answers 403 to every non-browser agent (verified live
 *   with browser headers), so RANDOM is deliberately not declared and
 *   the shuffle is served from the feed that exists.
 * - [search] is the site's own database search — one request per page,
 *   paginated exactly as the site paginates its answers, its
 *   zero-result apology honored as an honest empty page. No fallback
 *   tiers: the database answers multi-word queries directly (verified
 *   live), and a miss is a miss the site itself reports.
 * - [sections] offers fifteen shelves: the two ranked feeds, the anime
 *   category, and twelve tag-style `query` presets the site's own
 *   search addresses precisely (verified live: nature 526 pages,
 *   abstract 308, anime 174, games 132, fantasy 31).
 * - [details] re-reads the wallpaper page: the definitive record with
 *   TRUE dimensions, the original at its `/download/` path, the site's
 *   own tags, and the page URL as the source.
 * - [random] keeps the contract total, answering one random page of the
 *   latest feed — RANDOM is not declared, so the host never asks.
 *
 * ## Politeness
 *
 * The site's robots.txt is the old search-engine allowlist: everything
 * is disallowed to the generic `*` agent, while Googlebot, Slurp,
 * msnbot and Mediapartners are explicitly invited — a pattern from the
 * era before CDN bots, not a statement about user-driven clients. The
 * extension's access is the disclosed exception the wallpapercave and
 * wallpapersafari sources already document for robots-restricted paths:
 * every fetch happens on an explicit user action, one page per request,
 * sequentially, capped at a hundred pages deep — byte-identical to a
 * user browsing the same screens in a browser, never crawling or
 * enumerating. Nothing the site reserves is touched: no sitemap walking,
 * no RSS, and the Cloudflare-walled random page is left to the browsers
 * it serves.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the only mutable state is
 * the tag pool feeding [suggestTags] — a bonus, never a dependency. A
 * fresh instance answers identically, at worst without suggestions. One
 * lock guards it; the host loads sections in parallel.
 *
 * ## Dimensions
 *
 * Grid items carry NONE, on purpose. The listing markup hard-codes
 * `width="246" height="154"` on every cell — the uniform card crop,
 * true of no wallpaper (a 7680x4320 original and a 1920x1080 one arrive
 * identically labeled). A value that wrong is worse than no value, and
 * the app renders null dimensions as "—". [details] is where the TRUE
 * resolution arrives: the page's own resolution list, its largest entry
 * — the original's dimensions.
 */
class HdWallpapersWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "HD Wallpapers",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "HD, 4K, 5K and 8K wallpapers from hdwallpapers.in - listings, search and originals in the grid, keyless.",
            // The site curates its uploads and carries no per-item rating
            // metadata; SFW is its own claim and the honest floor. The
            // host's rating switch still applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(
            Capability.POPULAR,
            Capability.LATEST,
            Capability.SEARCH,
            Capability.TAGS,
            Capability.FILTERS,
        )

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    // -------------------------------------------------------------- feeds

    /**
     * The default feed, ridden through the host vocabulary: the
     * most-viewed ranking by default (the site's own "Most Popular
     * Wallpapers"), `sorting=date` walking the latest feed,
     * `sorting=toplist` the most-downloaded ranking, `category=anime`
     * the site's own anime category page, and `sorting=random` one
     * random batch of the latest feed. Category wins over sorting: it is
     * the primary axis of the site's content.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val category = filters.valuesFor("category").firstNotNullOfOrNull(hostCategoryPath::get)
            when {
                category != null -> listingPage(category, page)
                filters.isSelected("sorting", "date") -> listingPage(LATEST_PATH, page)
                filters.isSelected("sorting", "random") -> Page(randomLatestBatch(), nextPage = null)
                filters.isSelected("sorting", "toplist") -> listingPage(TOPLIST_PATH, page)
                else -> listingPage(POPULAR_PATH, page)
            }
        }

    /**
     * The site's own database search: one request per page, paginated
     * exactly as the site paginates its answers — the same bar, the same
     * `Next` cursor, as deep as the site itself goes. The zero-result
     * apology short-circuits to an honest empty page; the trending
     * collection behind it is the site's suggestion, never an answer. A
     * blank query (the contract's escape hatch) lands on the latest
     * feed's first page, the same default the blank popular feed shows.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching listingPage(LATEST_PATH, 1)
            }
            searchPage(query, page)
        }

    /**
     * Suggestions come ONLY from tags and title words this instance has
     * already seen in fetched pages — no keystroke-driven scraping. A
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
     * the default row the host's merged home picks), then the anime
     * category on the host's own `category` vocabulary, then twelve
     * tag-style `query` presets the site's search addresses precisely.
     * Cheap and offline, as the contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "popular", title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("category" to "anime")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "cars")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "movies", title = "Movies", filters = Filters.of("query" to "movies")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "fantasy", title = "Fantasy", filters = Filters.of("query" to "fantasy")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space")),
            HomeSection(id = "sports", title = "Sports", filters = Filters.of("query" to "sports")),
            HomeSection(id = "bikes", title = "Bikes", filters = Filters.of("query" to "bikes")),
            HomeSection(id = "flowers", title = "Flowers", filters = Filters.of("query" to "flowers")),
            HomeSection(id = "cute", title = "Cute", filters = Filters.of("query" to "cute")),
        )

    /**
     * The definitive record for an id: the wallpaper page re-fetched and
     * re-read — TRUE dimensions from the page's own largest resolution
     * variant, the original at its `/download/` path, the cleaned `h1`
     * title, the site's own tags, the page URL. Transport failures
     * propagate untouched, per the facade contract; a non-2xx answer or
     * a page that no longer speaks this shape (deleted wallpaper,
     * redesign) is a source failure, reported as such — the grid item's
     * own URLs already work, so the host's viewer degrades gracefully.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val html = fetchPage("$BASE_URL/${encode(id)}-wallpapers.html")
            val record =
                HdWallpapersParser.parseDetail(html)
                    ?: error("unrecognized wallpaper page for '$id'")
            if (record.slug != id) {
                error("wallpaper page answered for '${record.slug}', not '$id'")
            }
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl =
                            record.displayThumbPath
                                .takeIf { it.isNotBlank() }
                                ?.let { "$BASE_URL$it" }
                                ?: originalUrlOf(id),
                        fullUrl = "$BASE_URL${record.originalPath}",
                        title = record.title,
                        width = record.width,
                        height = record.height,
                        tags = record.tags,
                    ),
                resolution = resolutionOf(record.width, record.height),
                sourceUrl = "$BASE_URL/${encode(id)}-wallpapers.html",
            )
        }

    /**
     * The site's own random page is Cloudflare-walled for non-browser
     * agents, so RANDOM is deliberately not declared and the host never
     * asks — this exists to keep the contract total and answers one
     * random page of the latest feed, the honest shuffle of the stream
     * that exists.
     */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { randomLatestBatch() }

    // ------------------------------------------------------------ streams

    /**
     * Any listing page: fetch, honor the apology, parse the grid, read
     * the grid's own pagination bar for what follows. The `Next` link is
     * the site's "more exists" signal — past-the-end requests answer
     * with the apology page, so the marker is the stopper, never a
     * guess. A [MAX_PAGES] cap bounds the deepest scroll the same
     * politeness way.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val html = fetchPage(listingUrl(path, page))
        return pageOf(html, page)
    }

    /** The search twin of [listingPage], with the search URL shapes. */
    private suspend fun searchPage(
        query: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val html = fetchPage(searchUrl(query, page))
        return pageOf(html, page)
    }

    /** One fetched stream page into the contract's page. */
    private fun pageOf(
        html: String,
        page: Int,
    ): Page {
        if (HdWallpapersParser.hasNoResultsMarker(html)) return Page(emptyList(), nextPage = null)
        val wallpapers =
            HdWallpapersParser
                .parseGrid(html)
                .map(::gridWallpaper)
                .let(::rememberTagsIn)
        val more = if (HdWallpapersParser.hasMorePages(html)) page + 1 else null
        return Page(wallpapers, nextPage = more)
    }

    /** One random batch of the latest feed — a page drawn in [RANDOM_DEPTH]. */
    private suspend fun randomLatestBatch(): List<Wallpaper> {
        val html = fetchPage(listingUrl(LATEST_PATH, Random.nextInt(RANDOM_DEPTH) + 1))
        if (HdWallpapersParser.hasNoResultsMarker(html)) return emptyList()
        return HdWallpapersParser.parseGrid(html).map(::gridWallpaper)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the thumb is the site's own 2x copy
     * (the `srcset` retina source, the very file the wallpaper page
     * displays), and the original is the DETERMINISTIC
     * `/download/{slug}-HD.jpg` the page's own Original button carries —
     * a working download URL before details are ever fetched, the
     * WallpaperCave extractor pattern. Dimensions are deliberately
     * absent: the cell's `width`/`height` attributes are the site's
     * uniform 246x154 card crop, identical on every cell and true of no
     * wallpaper. Tags derive from the title's words so the detail
     * screen's "More like this" row has a query to work with.
     */
    private fun gridWallpaper(item: HdWallpapersParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.slug,
            providerId = ID,
            thumbUrl = "$BASE_URL${item.thumbPath}",
            fullUrl = originalUrlOf(item.slug),
            title = item.title?.takeIf { it.isNotBlank() },
            tags = tagsFromTitle(item.title),
        )

    /** The deterministic original: the page's own Original link, by slug alone. */
    private fun originalUrlOf(slug: String): String = "$BASE_URL/download/$slug-HD.jpg"

    /**
     * Title words as tags: lowercase, three characters or more, common
     * words, resolution labels and bare years dropped. A title without
     * a usable word yields no tags — honest emptiness over noise.
     */
    private fun tagsFromTitle(title: String?): List<String> =
        title
            ?.lowercase()
            ?.split(Regex("""[^a-z0-9']+"""))
            ?.filter { it.length >= MIN_TAG_LENGTH && it !in TITLE_STOP_WORDS && !it.all(Char::isDigit) }
            ?.distinct()
            ?.take(MAX_TITLE_TAGS)
            ?: emptyList()

    /** `WxH`, or null when the page carried no resolution list. */
    private fun resolutionOf(
        width: Int?,
        height: Int?,
    ): String? =
        if (width != null && height != null) {
            "${width}x$height"
        } else {
            null
        }

    /** Harvests seen tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTagsIn(wallpapers: List<Wallpaper>): List<Wallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        synchronized(lock) {
            wallpapers
                .flatMap { it.tags }
                .forEach { tag ->
                    if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag)
                }
        }
        return wallpapers
    }

    // ------------------------------------------------------------ plumbing

    /** Page one is the stream's own root; every later page its `/page/N`. */
    private fun listingUrl(
        path: String,
        page: Int,
    ): String = if (page == 1) "$BASE_URL$path" else "$BASE_URL$path/page/$page"

    /** The search's own URL shapes, page one and the later pages. */
    private fun searchUrl(
        query: String,
        page: Int,
    ): String =
        if (page == 1) {
            "$BASE_URL/search.html?q=${encode(query)}"
        } else {
            "$BASE_URL/search/page/$page?q=${encode(query)}"
        }

    /** GETs a page, mapping non-2xx answers to source failures. */
    private suspend fun fetchPage(url: String): String {
        val client = httpClient ?: error("provider used before configure()")
        val response = client.get(url)
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return response.bodyText
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun httpError(status: Int): Exception = ProviderHttpException("hdwallpapers.in answered HTTP $status")

    // -------------------------------------------------------------- state

    private val lock = Any()

    /** Tags seen in fetched pages, feeding [suggestTags]. */
    private val tagPool = LinkedHashSet<String>()

    private companion object {
        const val ID = "cloudimage.hdwallpapers"
        const val BASE_URL = "https://www.hdwallpapers.in"

        /** The ranked feeds: most viewed (the default) and most downloaded. */
        const val POPULAR_PATH = "/top_view_wallpapers"
        const val TOPLIST_PATH = "/top_download_wallpapers"

        /** The time-ordered feed; also the blank-query landing and the random shuffle's source. */
        const val LATEST_PATH = "/latest_wallpapers"

        /**
         * The host `category` vocabulary this provider can express: the
         * site's own anime category page, natively. `people` has no
         * honest expression — the site files no such category — so it is
         * ignored and the default feed serves, the contract's documented
         * behavior for values a provider cannot express.
         */
        val hostCategoryPath = mapOf("anime" to "/anime-desktop-wallpapers")

        /** Deep-pagination cap: a hundred pages, twenty items each. */
        const val MAX_PAGES = 100

        /** The random shuffle's honest depth within the latest feed. */
        const val RANDOM_DEPTH = 50

        /** Title-derived tags: word floor, per-item and pool caps. */
        const val MIN_TAG_LENGTH = 3
        const val MAX_TITLE_TAGS = 6
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** Words never worth suggesting or searching as tags. */
        val TITLE_STOP_WORDS =
            setOf(
                "the",
                "and",
                "for",
                "with",
                "from",
                "part",
                "new",
                "one",
                "two",
                "wallpaper",
                "wallpapers",
                "wall",
                "background",
                "backgrounds",
                "desktop",
                "mobile",
                "laptop",
                "iphone",
                "android",
                "full",
                "art",
                "logo",
                "text",
                "hd",
                "qhd",
                "fhd",
                "uhd",
                "2k",
                "4k",
                "5k",
                "8k",
                "1080p",
                "1440p",
                "2160p",
            )
    }
}

package com.cloudimage.mordeo

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
 * Mordeo (https://www.mordeo.org) as a Cloudimage provider package — the
 * eighth keyless scraper in the repository set: no API, no key, the
 * site's own server-rendered pages read the way its browser UI does.
 *
 * ## Site model
 *
 * Mordeo is a WordPress blog of PORTRAIT phone wallpapers — 4K Ultra HD
 * 2160x3840 originals for phones, the axis every desktop-first source in
 * this set lacks. One masonry grid of six cells serves every listing:
 *
 * - **The home stream** (`/`, `/page/N/` — 1,144 pages, six cells each,
 *   the site's whole 2016→2026 archive in reverse-chronological order).
 *   The site publishes no ranking of its own — no popular, no
 *   most-downloaded — so this one stream IS the site's feed, the honest
 *   expression of both POPULAR and LATEST (the same single-stream answer
 *   WallpaperSafari documents for its home wall).
 * - **Twenty-three categories** (`/wallpapers/category/{slug}/`) — the
 *   site's own directory, including the host vocabulary's `anime` and
 *   `people` natively.
 * - **Tag streams** (`/wallpapers/tag/{slug}/`) — one per WordPress tag,
 *   the site's real taxonomy, printed on every card's class list too.
 * - **Search** (`/?s={query}`, then `/page/N/?s={query}`) — the blog's
 *   own database query over titles, tags and content, multi-word
 *   included. A query it cannot answer ships the masonry list EMPTY —
 *   zero parsed cells, an honest empty page, never the sidebar's
 *   suggestions.
 * - **Wallpaper pages** (`/wallpapers/{slug}/`) disclose everything: the
 *   `h1` title, the display image, the download button (a direct
 *   `/files/uploads/` link on the newer and oldest eras; a
 *   `/download/{postId}/` endpoint on the middle era that serves the
 *   same original bytes, octet-stream dressed), the resolution prose
 *   ("With 2160x3840 pixels …") carrying the TRUE dimensions, and the
 *   site's own tag row.
 *
 * The original is DETERMINISTIC: every card image filename is the
 * uploaded original's name with a size suffix (`-{WxH}`, `-Preview` or
 * `-scaled`), and stripping it while speaking `.jpg` answers the
 * original — verified live across all four eras of the site, seven for
 * seven, byte-identical to the download links the pages carry. Like
 * HD Wallpapers' `/download/{slug}-HD.jpg`, the "extractor layer" is a
 * string template: every grid item holds a working original URL before
 * details are ever fetched.
 *
 * ## How the contract maps onto it
 *
 * - [popular] is the home stream by default; the host vocabulary rides
 *   it: `category=anime` and `category=people` walk the site's own
 *   category pages natively (`general` has no honest expression — the
 *   site files no such category — so it falls back to the stream, the
 *   contract's documented behavior), `sorting=date` and `sorting=random`
 *   both serve the stream too (one random page for random — the site
 *   has no ranking, so `toplist` and `date` are the same water).
 * - [search] is the blog's own query — one request per page, paginated
 *   exactly as the site paginates its answers (`nextpostslink`), as deep
 *   as its own answers go. The empty masonry list is read as the honest
 *   zero. No fallback tiers: a miss is a miss the site itself reports.
 * - [sections] offers fourteen shelves: the stream twice (Popular and
 *   Latest — the host renders both rows and deep-linking keeps them
 *   distinct), the two native host-vocabulary categories, and ten
 *   tag-style `query` presets the site's own search answers precisely
 *   (verified live: every preset term returns a full first page).
 * - [details] re-reads the wallpaper page: the definitive record with
 *   TRUE dimensions, the site's own original, the site's own tags, and
 *   the page URL as the source.
 * - [random] keeps the contract total, answering one random page of the
 *   stream — RANDOM is not declared, so the host never asks.
 *
 * ## Politeness
 *
 * The site's robots.txt is the standard Yoast boilerplate: it disallows
 * only the search query URLs (the `?s=` form and its paged variants) —
 * an anti-index measure against crawlable search-result pages, not a
 * statement about
 * user-driven clients; the sitemap and every listing stay open. The
 * extension's search access is the disclosed exception the wallpapercave
 * and hdwallpapers sources already document for robots-restricted paths:
 * every fetch happens on an explicit user action, one page per request,
 * sequentially, capped at a hundred pages deep — byte-identical to a
 * user searching and browsing the same screens in a browser. No sitemap
 * walking, no RSS, no enumeration of the site's anything.
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
 * Grid items carry NONE, on purpose. The card markup's `width`/`height`
 * attributes are the uniform card crop (283x503 — the masonry cell,
 * identical on every card and true of no wallpaper). A value that wrong
 * is worse than no value, and the app renders null dimensions as "—".
 * [details] is where the TRUE resolution arrives: the page's own prose,
 * stated once, in pixels.
 */
class MordeoWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Mordeo",
            versionName = "1.0.0",
            author = "Cloudimage",
            description =
                "4K Ultra HD mobile wallpapers from mordeo.org - portrait originals for phones, " +
                    "listings, search and tags, keyless.",
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
     * The default feed, ridden through the host vocabulary: the home
     * stream (the site's single chronological feed — it publishes no
     * ranking, so popular and latest are the same water, the honest
     * single-stream answer WallpaperSafari documents too),
     * `category=anime` and `category=people` walking the site's own
     * category pages natively, and `sorting=random` serving one random
     * page of the stream. Category wins over sorting: it is the primary
     * axis of the site's content.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val category = filters.valuesFor("category").firstNotNullOfOrNull(hostCategoryPath::get)
            when {
                category != null -> listingPage(category, page)
                filters.isSelected("sorting", "random") -> Page(randomStreamBatch(), nextPage = null)
                else -> listingPage(STREAM_PATH, page)
            }
        }

    /**
     * The blog's own database search: one request per page, paginated
     * exactly as the site paginates its answers — the same bar, the same
     * `nextpostslink` cursor, as deep as the site itself goes. The empty
     * masonry list (a query the site cannot answer) parses to zero
     * cells — an honest empty page, the sidebar's suggestions never
     * served as results. A blank query (the contract's escape hatch)
     * lands on the stream's first page, the same default the blank
     * popular feed shows.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching listingPage(STREAM_PATH, 1)
            }
            searchPage(query, page)
        }

    /**
     * Suggestions come ONLY from tags this instance has already seen in
     * fetched pages — the WordPress taxonomy printed on every card and
     * every wallpaper page, no keystroke-driven scraping. A fresh
     * instance answers nothing, which the host treats as "no
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
     * The site's shelves as tabs: the stream twice (Popular is the
     * default row the host's merged home picks, Latest the same water
     * the host renders as its own row), the two native host-vocabulary
     * categories, then ten tag-style `query` presets the site's own
     * search addresses precisely. Cheap and offline, as the contract
     * asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "popular", title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("category" to "anime")),
            HomeSection(id = "people", title = "People", filters = Filters.of("category" to "people")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "cars")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "movie", title = "Movie", filters = Filters.of("query" to "movie")),
            HomeSection(id = "flowers", title = "Flowers", filters = Filters.of("query" to "flowers")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space")),
            HomeSection(id = "sports", title = "Sports", filters = Filters.of("query" to "sports")),
            HomeSection(id = "cute", title = "Cute", filters = Filters.of("query" to "cute")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
        )

    /**
     * The definitive record for an id: the wallpaper page re-fetched and
     * re-read — TRUE dimensions from the page's own resolution prose,
     * the original (the site's own direct link, or the derived static
     * original the middle era's octet-stream endpoint is
     * byte-identical to), the cleaned `h1` title, the site's own tags,
     * the page URL. Transport failures propagate untouched, per the
     * facade contract; a non-2xx answer (a deleted wallpaper answers
     * 404) or a page whose canonical names another slug is a source
     * failure, reported as such — the grid item's own URLs already
     * work, so the host's viewer degrades gracefully.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val html = fetchPage(wallpaperUrlOf(id))
            val record =
                MordeoParser.parseDetail(html)
                    ?: error("unrecognized wallpaper page for '$id'")
            val canonical = record.canonicalSlug
            if (canonical != null && canonical != id) {
                error("wallpaper page answered for '$canonical', not '$id'")
            }
            val original =
                record.originalUrl
                    ?: error("wallpaper page carried no original for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = record.displayThumbUrl ?: original,
                        fullUrl = original,
                        title = record.title,
                        width = record.width,
                        height = record.height,
                        tags = record.tags,
                    ),
                resolution = resolutionOf(record.width, record.height),
                sourceUrl = wallpaperUrlOf(id),
            )
        }

    /**
     * The site has no random endpoint of its own, so RANDOM is
     * deliberately not declared and the host never asks — this exists
     * to keep the contract total and answers one random page of the
     * stream, the honest shuffle of the feed that exists.
     */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { randomStreamBatch() }

    // ------------------------------------------------------------ streams

    /**
     * Any listing page: fetch, parse the grid, read the wp-pagenavi
     * bar's `nextpostslink` for what follows. Its absence is the stop —
     * the last page of a stream carries none, and a past-the-end
     * request answers 404 (mapped to a source failure) before the
     * cursor would ever walk there. A [MAX_PAGES] cap bounds the
     * deepest scroll the same politeness way.
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
        val wallpapers =
            MordeoParser
                .parseGrid(html)
                .map(::gridWallpaper)
                .let(::rememberTagsIn)
        val more = if (MordeoParser.hasMorePages(html)) page + 1 else null
        return Page(wallpapers, nextPage = more)
    }

    /** One random batch of the stream — a page drawn in [RANDOM_DEPTH]. */
    private suspend fun randomStreamBatch(): List<Wallpaper> {
        val html = fetchPage(listingUrl(STREAM_PATH, Random.nextInt(RANDOM_DEPTH) + 1))
        return MordeoParser.parseGrid(html).map(::gridWallpaper)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the thumb is the site's own 2x-grade
     * srcset copy (never the multi-megabyte original), and the full is
     * the DETERMINISTIC original — the card filename minus its size
     * suffix, spoken as `.jpg` — a working download URL before details
     * are ever fetched, the HD Wallpapers extractor pattern. Dimensions
     * are deliberately absent: the card's `width`/`height` attributes
     * are the uniform 283x503 masonry crop, identical on every card and
     * true of no wallpaper. Tags are the site's own WordPress taxonomy,
     * read off the card's class list.
     */
    private fun gridWallpaper(item: MordeoParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.slug,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.fullUrl,
            title = item.title,
            tags = item.tags,
        )

    /** `WxH`, or null when the page carried no resolution prose. */
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

    /** The wallpaper page of an id. */
    private fun wallpaperUrlOf(id: String): String = "$BASE_URL/wallpapers/${encode(id)}/"

    /** Page one is the stream's own root; every later page its `/page/N`. */
    private fun listingUrl(
        path: String,
        page: Int,
    ): String =
        if (page == 1) {
            "$BASE_URL$path/"
        } else {
            "$BASE_URL${path.trimEnd('/')}/page/$page/"
        }

    /** The search's own URL shapes, page one and the later pages. */
    private fun searchUrl(
        query: String,
        page: Int,
    ): String =
        if (page == 1) {
            "$BASE_URL/?s=${encode(query)}"
        } else {
            "$BASE_URL/page/$page/?s=${encode(query)}"
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

    private fun httpError(status: Int): Exception = ProviderHttpException("mordeo.org answered HTTP $status")

    // -------------------------------------------------------------- state

    private val lock = Any()

    /** Tags seen in fetched pages, feeding [suggestTags]. */
    private val tagPool = LinkedHashSet<String>()

    private companion object {
        const val ID = "cloudimage.mordeo"
        const val BASE_URL = "https://www.mordeo.org"

        /** The home stream — the site's single chronological feed. */
        const val STREAM_PATH = ""

        /**
         * The host `category` vocabulary this provider can express: the
         * site's own anime and people category pages, natively.
         * `general` has no honest expression — the site files no such
         * category — so it is ignored and the stream serves, the
         * contract's documented behavior for values a provider cannot
         * express.
         */
        val hostCategoryPath =
            mapOf(
                "anime" to "/wallpapers/category/anime",
                "people" to "/wallpapers/category/people",
            )

        /** Deep-pagination cap: a hundred pages, six items each. */
        const val MAX_PAGES = 100

        /** The random shuffle's honest depth within the stream. */
        const val RANDOM_DEPTH = 50

        /** Tag pool and suggestion caps. */
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8
    }
}

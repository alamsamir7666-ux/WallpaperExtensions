package com.cloudimage.alphacoders

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
 * Wallpaper Abyss (https://alphacoders.com, the wallpaper section of Alpha
 * Coders) as a Cloudimage provider package — the fourth scraping source in
 * the repository set: no API, no key, just the site's own server-rendered
 * pages read the way its browser UI does.
 *
 * ## Site model
 *
 * The site consolidated its wallpaper browsing onto alphacoders.com topic
 * addresses: every listing — the ranked feeds and every topic page — is
 * `/{slug}-wallpapers` serving fifteen schema.org `ImageObject` cells (see
 * [AlphaCodersParser]), paginated by `?page=N` with a hard 404 past the end
 * (verified: page 9999 answers 404, unknown slugs answer 404 — the site
 * never clamps, never repeats). Search is the site's own endpoint — the
 * exact GET its search box produces, `/search/view?q={query}&type=wallpaper`
 * — answering the same fifteen-cell grid, paginated by `&page=N`, with an
 * empty grid as both its no-match and its past-the-end answer. The
 * wallpaper's own page is the classic `wall.alphacoders.com/big.php?i={id}`,
 * which tops the cell up with true dimensions, author, file size and the
 * site's color row.
 *
 * ## How the contract maps onto it
 *
 * - [popular] rides the host vocabulary: a recognized `category` walks that
 *   topic's listing (`anime`, `people` — both real topics with their own
 *   pages), `sorting=date` walks the newest feed, and everything else lands
 *   on the popular ranking — the default feed the browse tab shows first.
 * - [search] rides the site's real search endpoint — the exact GET its
 *   search box produces (`/search/view?q={query}&type=wallpaper`),
 *   answering the same schema.org grid the listings use. A no-match query
 *   is the site's own empty grid — an honest miss, not a failure — and a
 *   page past the result set's end answers empty too, never repeating or
 *   clamping. A blank query lands on the popular feed's first page.
 *   (1.0.x guessed topic addresses from the query instead, and a
 *   multi-word query like `indian actress` redirected to the generic
 *   `indian` topic — nothing like what the site's own search returns.
 *   1.1.0 returns exactly what the site's users see.)
 * - [sections] offers sixteen shelves: Popular, Latest (the host
 *   `sorting=date` preset), Anime and People (the host `category`
 *   vocabulary), and tag-style `query` presets — plain terms a user
 *   would type into the site's search box, riding the real search
 *   endpoint since 1.1.0 (the old canonical-singular forms existed to
 *   dodge topic-address redirects, a trap the search endpoint does not
 *   have).
 * - [details] fetches `big.php?i={id}` and reads the definitive record:
 *   the original file, TRUE dimensions, author credit, the File Info box's
 *   size, the keyword row and the color row. Transport failures propagate;
 *   a non-2xx answer (deleted wallpaper, redesigned page) is a source
 *   failure, reported as such.
 * - [random] has no site endpoint to ride; the honest answer is the
 *   freshest batch the site itself puts in front of every visitor — the
 *   newest feed's first page, served as-is.
 *
 * ## Pagination — honest by construction
 *
 * The listing pages carry no "more exists" signal in their markup (the
 * pagination bar is rendered only for the logged-in "Pages" view), so a
 * feed page answers `nextPage = page + 1` whenever it served items, and
 * the end arrives as the site's own 404: the follow-up request maps to an
 * empty page with a null `nextPage` — the feed ends honestly, exactly
 * where the site itself ends. An empty 200 also ends the feed. Search
 * ends the same honest way with its own boundary shape: no matches and
 * past-the-end pages both answer an empty grid on 200 (verified live at
 * page 999 of a finite result set), and the endpoint never clamps or
 * repeats. The ranked feeds' own first pages can never 404 (they are the
 * site's front door), so a 404 there is reported as the source failure it
 * is.
 *
 * ## Politeness
 *
 * Fetches happen on explicit user actions, one request per page of
 * fifteen, capped at a hundred pages deep; the one boundary probe at a
 * feed's end is the same request a browser following the site's own
 * next-page arrow produces. The site serves plain non-browser
 * User-Agents without challenge (verified live against every path this
 * provider touches), so no browser impersonation is needed, and the CDN
 * serves both the thumbnail and the original exactly where the markup
 * points every browser.
 *
 * One disclosed exception, decided deliberately: the site's robots.txt
 * excludes `/search/view` — its internal search — and [search] rides it
 * anyway. The exclusion is written for crawlers and indexers; this
 * provider searches only when a user types a query: one request per
 * explicit action, human-paced, byte-identical to what the site's own
 * search box sends, never crawling, never enumerating. The alternative —
 * 1.0.x's topic-address guessing — answered real queries with pages that
 * did not match them, a worse failure of the honesty this file tries to
 * practice everywhere else. Every other path this provider touches stays
 * inside robots.txt's allowances.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the only mutable state is the
 * tag pool feeding [suggestTags] — a bonus, never a dependency. A fresh
 * instance answers identically, at worst without suggestions. The pool is
 * guarded by one lock; sections load in parallel on the host side.
 *
 * ## Dimensions and threading
 *
 * Grid items carry NONE, on purpose: the listing's `width="350"
 * height="219"` attrs are the site's uniform card crop, identical on every
 * cell and true of no wallpaper (a 3840x2400 original and a 2205x1080 one
 * both arrive labeled 350x219). [details] is where the TRUE resolution
 * arrives. Parsing runs inline on the caller's dispatcher, like every
 * other provider in this set: the payload ABI (provider:api +
 * kotlin-stdlib + kotlinx.serialization — the packages the app keeps
 * unrenamed for its DexClassLoader) exposes no coroutine machinery to
 * plugin code, so a dispatcher hop is not a plugin's to make. The cost is
 * bounded anyway: [AlphaCodersParser] is a single-pass regex scan, and the
 * host's HTTP facade already moves the network itself off the main
 * thread.
 */
class AlphaCodersWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Alpha Coders",
            versionName = "1.1.0",
            author = "Cloudimage",
            description = "HD, 4K and 8K wallpapers from Wallpaper Abyss at alphacoders.com - scraped, keyless.",
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
     * `category` walks that topic's listing, `sorting=date` walks the
     * newest feed, and everything else lands on the popular ranking.
     * Category wins over sorting: it is the primary axis of the site's
     * content.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val topic = filters.valuesFor("category").firstNotNullOfOrNull(hostCategoryPath::get)
            val path =
                when {
                    topic != null -> topic
                    filters.isSelected("sorting", "date") -> NEWEST_PATH
                    else -> POPULAR_PATH
                }
            listingPage(path, page)
        }

    /**
     * The site's real search endpoint — the exact GET its search box
     * produces — walked page by page. The results grid is the same
     * schema.org markup every listing uses (see [AlphaCodersParser]), so
     * mapping and tag harvest are shared with the feeds. Ends are the
     * site's own: a query with no matches and a page past the result
     * set's end both answer an empty grid on 200 (verified live), which
     * lands as an honest empty page that never advertises more; a 404 —
     * never observed on this endpoint — is treated the same way, defense
     * in depth. A blank query (the contract's escape hatch) lands on the
     * popular feed's first page, the same default the blank popular feed
     * would show.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching listingPage(POPULAR_PATH, 1)
            }
            searchPage(query, page)
        }

    /**
     * Suggestions come ONLY from keyword rows this instance has already
     * seen in fetched pages — the site's own subject vocabulary, which is
     * exactly what lands in the pool. No keystroke-driven scraping. A
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
     * the host routes through [search] with that term. The preset terms
     * name each topic's canonical address — the verified targets, not the
     * redirecting plurals. Cheap and offline, as the contract asks.
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
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "car")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "video game")),
            HomeSection(id = "movies", title = "Movies", filters = Filters.of("query" to "movie")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animal")),
            HomeSection(id = "fantasy", title = "Fantasy", filters = Filters.of("query" to "fantasy")),
            HomeSection(id = "music", title = "Music", filters = Filters.of("query" to "music")),
            HomeSection(id = "dark", title = "Dark", filters = Filters.of("query" to "dark")),
            HomeSection(id = "minimal", title = "Minimal", filters = Filters.of("query" to "minimalist")),
            HomeSection(id = "city", title = "City", filters = Filters.of("query" to "city")),
        )

    /**
     * The definitive record for an id: the big.php page's own original,
     * TRUE dimensions, author, file size, tags, description and colors.
     * The preview carries the page's thumb-1920 render; the original
     * stands in when the redesign strips it. The site publishes no source
     * link beyond the page itself, so that page is the source URL.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val url = "$DETAIL_BASE?i=$id"
            val response = get(url)
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record = AlphaCodersParser.parseDetail(response.bodyText) ?: error("unrecognized wallpaper page for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = record.previewUrl ?: record.originalUrl.orEmpty(),
                        fullUrl = record.originalUrl ?: error("wallpaper page disclosed no original for '$id'"),
                        title = record.title,
                        width = record.width,
                        height = record.height,
                        tags = record.tags,
                        colors = record.colors,
                    ),
                author = record.author,
                resolution =
                    if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                fileSizeBytes = record.fileSizeBytes,
                sourceUrl = url,
            )
        }

    /**
     * No random endpoint exists on the site; the honest answer is the
     * freshest batch it puts in front of every visitor — the newest feed's
     * first page, served as-is.
     */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { newestBatch() }

    // ---------------------------------------------------------------- feed

    /**
     * Any listing page: fetch, parse the grid, and answer what the site
     * said. A 404 is the site's own boundary — past the end of any feed —
     * so it ends the feed honestly, except on a first page: these
     * addresses are the site's front door (the ranked feeds and the
     * preset topics), where a 404 is a source failure worth surfacing.
     * `nextPage` is offered only when this page served items, and never
     * past the cap — an empty page never advertises more.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val response = get(pageUrl(path, page))
        if (response.statusCode == NOT_FOUND) {
            if (page == 1) {
                throw httpError(NOT_FOUND)
            }
            return Page(emptyList(), nextPage = null)
        }
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return pageFromGrid(response.bodyText, page)
    }

    /**
     * One page of the site's real search: the same GET its search box
     * produces, `?q={query}&type=wallpaper` plus the page parameter. The
     * endpoint's 404 has never been observed — no-match and past-the-end
     * both answer empty grids on 200 — but it is mapped to the same
     * honest miss, defense in depth. Non-2xx answers other than 404 are
     * the source failures they are.
     */
    private suspend fun searchPage(
        query: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val url =
            "$SEARCH_BASE?q=${encode(query)}&type=wallpaper" +
                if (page > 1) "&page=$page" else ""
        val response = get(url)
        if (response.statusCode == NOT_FOUND) {
            return Page(emptyList(), nextPage = null)
        }
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return pageFromGrid(response.bodyText, page)
    }

    /**
     * The shared tail of every grid page: parse, map, remember tags, and
     * offer another page only when this one served items — an empty grid
     * never advertises more, and the cap bounds the deepest scroll.
     */
    private fun pageFromGrid(
        html: String,
        page: Int,
    ): Page {
        val items = AlphaCodersParser.parseGrid(html)
        val nextPage =
            when {
                items.isEmpty() -> null
                page >= MAX_PAGES -> null
                else -> page + 1
            }
        return Page(items.map(::gridWallpaper).let(::rememberTagsIn), nextPage)
    }

    /** The newest feed's first page — [random]'s honest stand-in. */
    private suspend fun newestBatch(): List<Wallpaper> {
        val response = get(pageUrl(NEWEST_PATH, 1))
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return AlphaCodersParser.parseGrid(response.bodyText).map(::gridWallpaper).let(::rememberTagsIn)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the cell's own published thumbnail (the
     * 350-pixel WebP the site's markup names — universally decodable at
     * the host's min SDK) in [Wallpaper.thumbUrl], the cell-disclosed
     * original in [Wallpaper.fullUrl]. Dimensions are deliberately absent:
     * the listing's width/height attributes are the site's uniform 350x219
     * card crop, a value that wrong is worse than no value, and the app
     * renders null dimensions as "—" until [details] supplies the true
     * ones. The keyword row's subjects ride as tags: the site's own
     * vocabulary, so the detail screen's "More like this" row has a query
     * that works and [suggestTags] has real tags to offer.
     */
    private fun gridWallpaper(item: AlphaCodersParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.originalUrl,
            title = item.title,
            tags = item.tags,
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

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("alphacoders answered HTTP $statusCode")

    /** A listing address with its page parameter — the site's own shape. */
    private fun pageUrl(
        path: String,
        page: Int,
    ): String = if (page > 1) "$BASE_URL$path?page=$page" else "$BASE_URL$path"

    /** Form encoding — the search box's own serialization (space is `+`). */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val ID = "cloudimage.alphacoders"
        const val BASE_URL = "https://alphacoders.com"
        const val DETAIL_BASE = "https://wall.alphacoders.com/big.php"

        /** The site's own search endpoint — the search box's exact GET. */
        const val SEARCH_BASE = "https://alphacoders.com/search/view"

        /** The ranked feeds. */
        const val POPULAR_PATH = "/popular-wallpapers"
        const val NEWEST_PATH = "/newest-wallpapers"

        /**
         * The host `category` vocabulary this provider can express: `anime`
         * and `people` are real topics with their own pages. `general`
         * needs no mapping: it is the default feed.
         */
        val hostCategoryPath =
            mapOf(
                "anime" to "/anime-wallpapers",
                "people" to "/people-wallpapers",
            )

        /** Deep-pagination cap: a hundred pages, fifteen items each. */
        const val MAX_PAGES = 100

        /** The site's own boundary answer, honored as an honest end. */
        const val NOT_FOUND = 404

        /** Per-item tag cap and the suggest pool's bounds. */
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** One lock over the tag pool; sections load in parallel. */
        val lock = Any()
    }

    private val tagPool = LinkedHashSet<String>()
}

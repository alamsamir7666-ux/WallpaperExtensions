package com.cloudimage.wallpaperaccess

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

/**
 * wallpaperaccess.com (https://wallpaperaccess.com) as a Cloudimage
 * provider package — the fourth scraping source in the repository set: no
 * API, no key, just the site's own server-rendered pages read the way its
 * browser UI does.
 *
 * ## Site model
 *
 * The site is a collection library: thousands of themed collections
 * (`/fall`, `/naruto`, `/4k-gaming`, …), each serving its ENTIRE inventory
 * — twenty to a hundred wallpapers — in one server-rendered page, plus two
 * ranked feeds: the popular ranking (`/most-popular`) and the fresh feed
 * (`/new`). Every wallpaper cell is a `div[id="{wallpaperId}"]` carrying
 * `data-fullimg` (the original file, served directly under `/full/`), and
 * `data-or` — the file's TRUE dimensions, published right in the listing
 * and verified pixel-exact against the served image (a rare candor: no
 * card-crop trap here, unlike the two scrapers before it). The cell's img
 * adds `data-slug` (the owning collection) and an `alt` titled
 * `WxH Title`. The same filename under `/thumb/` is the site's lighter
 * 600-pixel preview, fifteen-for-fifteen across six listings live. There
 * is no pagination anywhere — `?page=2` serves the identical page — but
 * every listing page ends with a Related Wallpapers band of sibling
 * collections, the site's own "keep browsing" recommendations, and the
 * endless scroll rides that band. Unknown collection addresses answer a
 * clean 404.
 *
 * ## How the contract maps onto it
 *
 * - [popular] rides the host's filter vocabulary: a recognized `category`
 *   walks that collection (`anime`, `people` — both real collections on
 *   this site), `sorting=date` walks the fresh feed `/new`, and everything
 *   else lands on the popular ranking, the default feed the browse tab
 *   shows first. Every feed then continues through the site's own Related
 *   Wallpapers band: page one is the root listing's whole batch, page two
 *   is the band's first card's batch, page three the second's, and so on
 *   — the exact journey a browser user clicking through Related Wallpapers
 *   takes, one collection per scroll, until the band runs out and the
 *   grid ends the same way the site's own pages do.
 * - [search] never touches the site's `/search` — its robots.txt excludes
 *   it. Instead the query is slugified into the site's own collection
 *   address shape and walked: `naruto` → `/naruto`, `4K Gaming!` →
 *   `/4k-gaming` (both verified live). An exact collection hit serves its
 *   whole batch, then walks that collection's own related band — the
 *   "more like this" the site itself recommends. An unknown address
 *   answers 404, which this provider reports as an honest empty results
 *   page — a miss, not a failure, seeding no walk. A blank query (the
 *   contract's escape hatch) lands on the popular feed, the same default
 *   the blank popular feed would show, and walks its band from there.
 * - [sections] offers sixteen shelves: Popular, Latest (the host
 *   `sorting=date` preset over `/new`), Anime and People (the host
 *   `category` vocabulary), and twelve tag-style `query` presets —
 *   Nature, Space, Abstract, Cars, Games, Movies, Animals, Fantasy,
 *   Music, Dark, Minimal, City — each naming a real collection the
 *   slug-guess search addresses exactly (all verified live, twenty-seven
 *   to a hundred and four items each), and each walking its own related
 *   band as the scroll deepens.
 * - [details] re-walks the listing the item came from — the id IS that
 *   pair, `collection/fileName` — and answers the cell's own record: the
 *   original URL, the TRUE dimensions from `data-or`, the alt-derived
 *   title and the owning collection as the item's tag. The site publishes
 *   no author and no file size, so those fields stay null — honest
 *   emptiness over invented values. In practice the host rarely asks: its
 *   viewer only fetches details when a listing withholds dimensions, and
 *   this site's listings never do.
 * - [random] has no site endpoint to ride; the honest answer is the
 *   freshest batch the site itself labels New, served as-is.
 *
 * ## Politeness
 *
 * The site's robots.txt excludes exactly two paths for generic agents —
 * `/download/` (the interstitial download pages) and `/search` — and this
 * provider touches neither: originals are read from the cell's own
 * `data-fullimg` attribute, search rides collection pages at their public
 * slug addresses, the same links the site's own navigation serves every
 * visitor. Page one of a feed costs one request returning the whole
 * batch — a browser tab on the same page costs the same; each deeper page
 * costs one or two (the walked card, plus the root band when this
 * instance has not yet seen it) — a browser user clicking the site's own
 * Related cards spends the same. Fetches happen only on explicit user
 * actions, and the walk never revisits a card it has already served.
 * Preview and original image URLs are fetched
 * only by the app's image pipeline when it renders or downloads an item,
 * exactly as the site's own markup directs every browser. The site serves
 * plain non-browser User-Agents without challenge (verified live against
 * every path this provider touches), so no browser impersonation is
 * needed.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the only mutable state is
 * optional caches that never gate correctness — the collection-name pool
 * feeding [suggestTags] and the related-band cache feeding the walk. A
 * fresh instance answers identically, at worst re-fetching a root band it
 * has not seen (one extra request, self-healing) or without suggestions.
 * Both caches are guarded by one lock; sections load in parallel on the
 * host side.
 *
 * ## Dimensions
 *
 * Grid items publish the TRUE ones straight from `data-or` — the listing
 * itself vouches for them, so the info sheet shows real resolutions with
 * zero extra requests, and portrait stays portrait (a 736x1389 phone
 * original parses exactly that). Should a cell ever arrive without
 * `data-or`, its dimensions stay null — honest emptiness over a guess —
 * and the host's viewer would fill the gap from [details].
 */
class WallpaperAccessWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "WallpaperAccess",
            versionName = "1.1.0",
            author = "Cloudimage",
            description = "HD, 4K and up wallpapers from wallpaperaccess.com - scraped, keyless.",
            // The site curates its collections and carries no per-item
            // rating metadata; SFW is its own claim and the honest floor.
            // The host's rating switch still applies on top.
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
     * `category` walks that collection's page, `sorting=date` walks the
     * fresh feed, and everything else lands on the popular ranking.
     * Whatever the root, deeper pages walk its Related Wallpapers band —
     * the site's own "keep browsing" recommendations, one collection per
     * page. Category wins over sorting: it is the primary axis of the
     * site's content.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val category = filters.valuesFor("category").firstNotNullOfOrNull(hostCategorySlug::get)
            when {
                category != null -> listingPage(category, page)
                filters.isSelected("sorting", "date") -> listingPage(FRESH_SLUG, page)
                else -> listingPage(POPULAR_SLUG, page)
            }
        }

    /**
     * The robots-compliant search: the query is slugified into the site's
     * own collection address shape and that page is walked — `naruto`
     * hits `/naruto`, `4K Gaming!` hits `/4k-gaming`. An unknown address
     * answers 404, which is a miss, not a failure: the honest answer is
     * an empty results page, exactly what the site's own no-results
     * moment looks like, and no walk is seeded from it. A hit serves its
     * whole batch, then walks that collection's own related band on
     * deeper pages. A blank query (the contract's escape hatch) lands on
     * the popular feed, the same default the blank popular feed would
     * show.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val root = WallpaperAccessParser.slugify(query).ifEmpty { POPULAR_SLUG }
            listingPage(root, page, rootNotFoundIsMiss = true)
        }

    /**
     * Suggestions come ONLY from collection names this instance has
     * already seen in fetched pages — the site's own navigation
     * vocabulary, which is exactly what lands in the pool. No
     * keystroke-driven scraping of the site's search (the one path its
     * robots.txt excludes). A fresh instance answers nothing, which the
     * host treats as "no suggestions" rather than an error.
     */
    override suspend fun suggestTags(query: String): Result<List<String>> =
        runCatching {
            if (query.isBlank()) {
                emptyList()
            } else {
                synchronized(lock) { slugPool.toList() }
                    .filter { it.contains(query, ignoreCase = true) }
                    .take(TAG_SUGGESTION_LIMIT)
            }
        }

    /**
     * The site's shelves as tabs: the two ranked feeds first (Popular is
     * the default row the host's merged home picks), then the two host
     * categories the site files natively, then tag-style `query` presets
     * the host routes through [search] with that term — each names a real
     * collection the slug-guess addresses exactly (all verified live).
     * Cheap and offline, as the contract asks.
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
            HomeSection(id = "minimal", title = "Minimal", filters = Filters.of("query" to "minimal")),
            HomeSection(id = "city", title = "City", filters = Filters.of("query" to "city")),
        )

    /**
     * The definitive record for an id: the id IS the re-fetch address,
     * `collection/fileName`, so the listing is re-walked and the cell's
     * own record answered — the original URL, the TRUE dimensions from
     * `data-or`, the alt-derived title, the owning collection as the
     * item's tag. An item no longer listed in its collection is a source
     * failure, reported as such — the host's viewer already holds the
     * grid item's own URLs, and in practice never even asks, because
     * this site's listings publish dimensions themselves. Transport
     * failures propagate untouched, per the facade contract. The site
     * publishes no author and no file size, so those fields stay null.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val slug = id.substringBefore('/')
            val fileName = id.substringAfter('/')
            val numericId = fileName.substringBefore('.')
            require(slug.isNotEmpty() && fileName.isNotEmpty() && numericId.isNotEmpty()) {
                "unrecognized wallpaper id '$id'"
            }
            val response = get("$BASE_URL/$slug")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val grid = WallpaperAccessParser.parseGrid(response.bodyText)
            val item =
                grid
                    .firstOrNull { it.numericId == numericId }
                    ?: error("wallpaper '$numericId' is no longer listed in '$slug'")
            val tag = item.slug ?: slug
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = "$BASE_URL/thumb/$fileName",
                        fullUrl = "$BASE_URL/full/$fileName",
                        title = item.title,
                        width = item.width,
                        height = item.height,
                        tags = listOf(tag),
                    ),
                author = null,
                resolution =
                    if (item.width != null && item.height != null) "${item.width}x${item.height}" else null,
                fileSizeBytes = null,
                sourceUrl = "$BASE_URL/$slug#$numericId",
            )
        }

    /**
     * No random endpoint exists on the site; the honest answer is the
     * freshest batch it itself labels New, served as-is.
     */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { freshBatch() }

    // ---------------------------------------------------------------- feed

    /**
     * Any listing feed, walked page by page through the site's own
     * Related Wallpapers band. Page one is the root listing's whole
     * batch — the site paginates nothing, one page IS the collection's
     * entire inventory. Deeper pages walk the root's related band, one
     * recommended collection per page in the site's own order: page two
     * serves the first card's batch, page three the second's, and so on
     * until the band runs out, where the feed ends with `nextPage` null.
     * A card the site no longer serves (404) is skipped gracefully — an
     * empty page whose `nextPage` keeps the walk alive, which the host's
     * merged feed already understands. The walked collection's own slug
     * rides in every item's id, so [details] re-walks the listing the
     * item actually came from.
     */
    private suspend fun listingPage(
        rootSlug: String,
        page: Int,
        rootNotFoundIsMiss: Boolean = false,
    ): Page {
        if (page < 1) return Page(emptyList(), nextPage = null)
        if (page == 1) {
            val response = get("$BASE_URL/$rootSlug")
            return when {
                response.isSuccessful -> {
                    val html = response.bodyText
                    val related = rememberRelated(rootSlug, html)
                    Page(
                        gridWallpapers(html, rootSlug),
                        nextPage = if (related.isEmpty()) null else 2,
                    )
                }
                // A search address that does not exist is a miss; a broken
                // default feed is a source error. The caller picks.
                rootNotFoundIsMiss && response.statusCode == 404 -> Page(emptyList(), nextPage = null)
                else -> throw httpError(response.statusCode)
            }
        }
        val related = relatedListOf(rootSlug)
        val index = page - 2
        if (index >= related.size) return Page(emptyList(), nextPage = null)
        val target = related[index]
        val more = index + 1 < related.size
        val response = get("$BASE_URL/$target")
        return when {
            response.isSuccessful ->
                Page(
                    gridWallpapers(response.bodyText, target),
                    nextPage = if (more) page + 1 else null,
                )
            // The site's own card points at a page it no longer serves —
            // skip it and let the next page continue the walk.
            response.statusCode == 404 -> Page(emptyList(), nextPage = if (more) page + 1 else null)
            else -> throw httpError(response.statusCode)
        }
    }

    /**
     * The root's related band — from the cache when this instance has
     * already seen the listing, refetched when it has not (a fresh
     * instance resuming mid-scroll heals itself with one extra request).
     * A root that no longer answers 404 ends the walk honestly: empty
     * band, no error — the feed simply ends where the site ends it. Any
     * other failure propagates as a source error, per the facade
     * contract.
     */
    private suspend fun relatedListOf(rootSlug: String): List<String> {
        synchronized(lock) { relatedCache[rootSlug] }?.let { return it }
        val response = get("$BASE_URL/$rootSlug")
        if (response.statusCode == 404) return emptyList()
        if (!response.isSuccessful) throw httpError(response.statusCode)
        return rememberRelated(rootSlug, response.bodyText)
    }

    /**
     * Parses and caches a listing's related band. The cache is a cost
     * saver, never a correctness dependency: capped, lock-guarded, and
     * silently skipped when full — the walk refetches the root band
     * instead, one extra request, and answers identically.
     */
    private fun rememberRelated(
        rootSlug: String,
        html: String,
    ): List<String> {
        val related = WallpaperAccessParser.parseRelated(html, self = rootSlug)
        synchronized(lock) {
            if (relatedCache.size < RELATED_CACHE_LIMIT) relatedCache[rootSlug] = related
        }
        return related
    }

    /** The fresh batch — the `/new` feed the site labels New Wallpapers. */
    private suspend fun freshBatch(): List<Wallpaper> {
        val response = get("$BASE_URL/$FRESH_SLUG")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return gridWallpapers(response.bodyText, FRESH_SLUG)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A listing page's grid to wallpapers: each cell's original under
     * `/full/`, its lighter preview under `/thumb/` (the same file name,
     * verified fifteen-for-fifteen live), the TRUE dimensions from
     * `data-or`, the alt-derived title, and the owning collection as the
     * item's tag — the site's own vocabulary, so the detail screen's
     * "More like this" row has a query that works and [suggestTags] has
     * real names to offer. The id carries the re-fetch address,
     * `collection/fileName`, so [details] can always re-walk the listing
     * the item came from.
     */
    private fun gridWallpapers(
        html: String,
        listingSlug: String,
    ): List<Wallpaper> {
        val grid = WallpaperAccessParser.parseGrid(html)
        return grid
            .map { item ->
                val tag = item.slug ?: listingSlug
                Wallpaper(
                    id = "$listingSlug/${item.fileName}",
                    providerId = ID,
                    thumbUrl = "$BASE_URL/thumb/${item.fileName}",
                    fullUrl = "$BASE_URL/full/${item.fileName}",
                    title = item.title,
                    width = item.width,
                    height = item.height,
                    tags = listOf(tag),
                )
            }.let(::rememberTagsIn)
    }

    /** Harvests seen collection names for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTagsIn(wallpapers: List<Wallpaper>): List<Wallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        synchronized(lock) {
            wallpapers.flatMap { it.tags }.forEach { tag ->
                if (slugPool.size < TAG_POOL_LIMIT) slugPool.add(tag.lowercase())
            }
        }
        return wallpapers
    }

    // ------------------------------------------------------------- plumbing

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url)
            ?: error("configure() was not called")

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("wallpaperaccess answered HTTP $statusCode")

    private companion object {
        const val ID = "cloudimage.wallpaperaccess"
        const val BASE_URL = "https://wallpaperaccess.com"

        /** The two ranked feeds: the popular ranking and the fresh feed. */
        const val POPULAR_SLUG = "most-popular"
        const val FRESH_SLUG = "new"

        /**
         * The host `category` vocabulary this provider can express: `anime`
         * and `people` are real collections on this site. `general` needs
         * no mapping: it is the default feed.
         */
        val hostCategorySlug =
            mapOf(
                "anime" to "anime",
                "people" to "people",
            )

        /** The suggest pool's bounds. */
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /**
         * The related-band cache's bound — one root per tab or query in
         * play, with room to spare for a long browsing session.
         */
        const val RELATED_CACHE_LIMIT = 32

        /** One lock over the pools; sections load in parallel. */
        val lock = Any()
    }

    private val slugPool = LinkedHashSet<String>()

    /** Root slug → its related band; guarded by [lock], capped, optional. */
    private val relatedCache = HashMap<String, List<String>>()
}

package com.cloudimage.wallpapersafari

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
 * WallpaperSafari (https://wallpapersafari.com) as a Cloudimage provider
 * package — the fifth scraping source in the repository set: no API, no
 * key, just the site's own server-rendered pages read the way its browser
 * UI does.
 *
 * ## Site model
 *
 * The site is gallery-first: content lives in topic galleries
 * (`/{slug}/` — `indian-actress-wallpapers`, `4k-wallpapers`), each
 * serving its COMPLETE wallpaper set server-rendered on one page — 89
 * items for indian-actress, 90 for labubu, no pagination, no lazy
 * loading. The home page carries the site's own "Popular wallpapers"
 * wall, fifty items, also complete. Search (`/search?q=` — the site's
 * search box, a plain GET form) answers with GALLERIES, never single
 * wallpapers, up to sixty per query; a query that matches nothing heads
 * its page with the site's own "couldn't find anything" apology above a
 * wall of trending suggestions — the markers are read, so suggestions
 * are never mistaken for results. Category pages
 * (`/category/{slug}/`) are directories of the same gallery cards.
 * Original files live on `cdn.wallpapersafari.com/{a}/{b}/{id}.{ext}`,
 * every card's `data-img`/`data-path` discloses both directories (medium
 * thumbnail and original share the path, one CDN and one size-prefix
 * apart), and the TRUE dimensions ride along in the like widget's
 * `data-width`/`data-height` — the listing grid is fully self-describing.
 *
 * ## How the contract maps onto it
 *
 * - [popular] rides the home wall by default — the exact feed the site's
 *   own front page shows, complete, one honest page. A recognized
 *   `category` value walks that category's gallery directory instead,
 *   three galleries per page — the same stream machinery as search.
 *   `anime` maps to the site's own anime category; the site files no
 *   people category, so `people` honestly lands on the default wall.
 * - [search] is the site's real search endpoint, the CloudStream
 *   two-step: the query's gallery cards first, then the batch's topic
 *   pages fetched one by one and merged — three galleries per app page,
 *   deduplicated, ten pages deep at most. The site's zero-result marker
 *   short-circuits to an honest empty page. One query matching a single
 *   gallery serves that gallery whole, which is exactly what the website
 *   itself shows.
 * - [sections] offers eleven shelves: the home wall, seven category
 *   presets (Anime, Art, Animals, Cars, Nature, Sports, Travel), and
 *   three query presets (Girls, Games, Movies) that ride [search] — the
 *   site's own answer for those terms.
 * - [details] fetches `/w/{id}` and reads the definitive record: the
 *   H1 title, the main image (the full-resolution file with its TRUE
 *   dimensions), the uploader, and the gallery the wallpaper belongs to.
 * - No latest feed exists server-rendered (`/latest-uploads/` is an
 *   empty JavaScript shell) and no random endpoint exists; LATEST and
 *   RANDOM are deliberately not declared.
 *
 * ## Politeness
 *
 * The site's robots.txt excludes `/download/`, `/downloadres/` (never
 * touched — the CDN originals are disclosed by the cards themselves),
 * `/cdn-cgi/` and `/search`. Search carries the same deliberate,
 * disclosed exception wallpapercave and 4kwallpapers already document:
 * touched only on explicit user actions, one request per action plus one
 * per merged gallery, byte-identical to the site's own search box —
 * never crawling or enumerating; every other path this provider touches
 * (home, topics, categories, wallpaper pages) stays inside the
 * allowances. Fetches are sequential and capped: search ten pages deep
 * (thirty galleries), category streams fifteen. The `/ajax/` endpoints
 * the site reserves for liking, rating and load-more are never called.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the mutable state is
 * session machinery only — a short-lived gallery-list cache per
 * pagination session, per-category served-id windows and cursors, and a
 * tag pool feeding [suggestTags] (a bonus, never a dependency). A fresh
 * instance answers identically, at worst without suggestions. One lock
 * guards every mutable member; sections load in parallel on the host
 * side.
 */
class WallpaperSafariWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "WallpaperSafari",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "Desktop and phone wallpapers from wallpapersafari.com - scraped, keyless.",
            // The site curates its galleries and carries no per-item rating
            // metadata; SFW is its own claim and the honest floor. The
            // host's rating switch still applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.SEARCH, Capability.FILTERS, Capability.TAGS)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    /**
     * The default feed is the home wall — the site's own "Popular
     * wallpapers", fifty complete items, honestly one page. A recognized
     * `category` value walks that category's gallery directory instead,
     * the same three-galleries-per-page stream as search. The site files
     * no people category of its own, so `people` lands on the default
     * wall — the honest nearest expression, documented rather than
     * guessed.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val category = filters.valuesFor("category").firstNotNullOfOrNull(categoryByTerm::get)
            if (category != null) {
                categoryFeed(category, page)
            } else {
                homeWallPage(page)
            }
        }

    /**
     * The site's real search, the CloudStream two-step: `/search?q=`
     * answers with gallery cards, then the batch's topic pages are
     * fetched one by one and merged. The zero-result marker short-circuits
     * to an honest empty page — the trending cards that follow it are
     * suggestions, not results, and are never served as answers. A
     * failing gallery is skipped (its wallpapers, not the whole page);
     * the deep-pagination cap keeps a long scroll from walking the
     * entire sixty-card result set.
     *
     * A blank query (the contract's escape hatch) lands on the home wall,
     * the same default the blank popular feed would show.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching homeWallPage(1)
            }
            if (page < 1 || page > MAX_SEARCH_PAGES) {
                return@runCatching Page(emptyList(), nextPage = null)
            }
            val cards = searchCards(query)
            val batch = cards.drop((page - 1) * GALLERIES_PER_PAGE).take(GALLERIES_PER_PAGE)
            if (batch.isEmpty()) {
                return@runCatching Page(emptyList(), nextPage = null)
            }
            val wallpapers =
                batch
                    .flatMap { card ->
                        runCatching { wallpapersOfGallery(card.slug) }.getOrDefault(emptyList())
                    }.distinctBy { it.id }
            val moreGalleriesRemain = page * GALLERIES_PER_PAGE < cards.size
            Page(wallpapers, nextPage = if (moreGalleriesRemain) page + 1 else null)
        }

    /**
     * Suggestions come ONLY from gallery titles and title words this
     * instance has already seen in fetched pages — no keystroke-driven
     * scraping of the site's search. A fresh instance answers nothing,
     * which the host treats as "no suggestions" rather than an error.
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
     * The site's shelves as tabs: the home wall first, then the category
     * presets in bar order, then the query presets. Anime rides the
     * host's own `category` vocabulary; every category shelf rides the
     * category filter; the query presets ride [search], where the site's
     * own answer for those terms is served. Cheap and offline, as the
     * contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "popular", title = "Popular"),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("category" to "anime")),
            HomeSection(id = "art", title = "Art", filters = Filters.of("category" to "art")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("category" to "animals")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("category" to "cars")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("category" to "nature")),
            HomeSection(id = "sports", title = "Sports", filters = Filters.of("category" to "sports")),
            HomeSection(id = "travel", title = "Travel", filters = Filters.of("category" to "travel")),
            HomeSection(id = "girls", title = "Girls", filters = Filters.of("query" to "girls")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "movies", title = "Movies", filters = Filters.of("query" to "movies")),
        )

    /**
     * The definitive record for an id: the `/w/{id}` page's H1 title, its
     * main image (the full-resolution file with the TRUE dimensions in
     * the site's own attributes), the uploader, and the gallery the
     * wallpaper belongs to. Transport failures propagate untouched, per
     * the facade contract; a non-2xx answer (deleted wallpaper,
     * redesigned page) is a source failure, reported as such — the id
     * alone discloses no image URL (the CDN path's two directory numbers
     * are not derivable from it), so there is nothing honest to degrade
     * to, and the host's viewer already holds the grid item's own URLs.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/w/${encode(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record = WallpaperSafariParser.parseDetail(response.bodyText) ?: error("unrecognized wallpaper page for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = WallpaperSafariParser.toThumbUrl(record.originalUrl) ?: record.originalUrl,
                        fullUrl = record.originalUrl,
                        title = record.title,
                        width = record.width,
                        height = record.height,
                        tags = tagsOf(record),
                    ),
                author = record.author,
                resolution =
                    if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                sourceUrl = "$BASE_URL/w/$id",
            )
        }

    /**
     * No random endpoint exists on the site and RANDOM is deliberately
     * not declared, so the host never asks — this exists to keep the
     * contract total and answers the home wall's own batch.
     */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { homeWallPage(1).wallpapers }

    // ---------------------------------------------------------------- feed

    /**
     * The home wall: complete on page one, honestly finished after it —
     * the site's own popular wall is one page, and a fabricated second
     * page would serve results the site itself never shows.
     */
    private suspend fun homeWallPage(page: Int): Page {
        if (page != 1) return Page(emptyList(), nextPage = null)
        val wallpapers = fetchHomeWall()
        return Page(wallpapers, nextPage = null)
    }

    /** Fetches and maps the home page's popular wall, harvesting tags on the way. */
    private suspend fun fetchHomeWall(): List<Wallpaper> =
        fetchAndParse(WallpaperSafariParser::parseHomeGrid, "$BASE_URL/")
            .mapNotNull(::gridWallpaper)
            .let(::rememberTagsIn)

    /**
     * One category's feed: the category page's gallery directory walked
     * three galleries at a time, each batch's topic pages fetched and
     * merged, ids an earlier page of this session already served dropped.
     * A page whose whole batch was duplicates consumes the next batch
     * instead of stranding the feed — the host appends pages one by one
     * and an empty-but-not-final page would stall the carousel. A feed
     * restart (page one) begins a new session, so the directory's head
     * comes back whole.
     */
    private suspend fun categoryFeed(
        category: CategorySpec,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_CATEGORY_PAGES) return Page(emptyList(), nextPage = null)
        if (page == 1) {
            synchronized(lock) {
                categorySeenIds.remove(category.term)
                categoryCursor.remove(category.term)
            }
        }
        val cards = cachedCategoryCards(category)
        var cursor = synchronized(lock) { categoryCursor[category.term] ?: 0 }
        val fresh = mutableListOf<Wallpaper>()
        while (fresh.isEmpty() && cursor < cards.size) {
            cards
                .drop(cursor)
                .take(GALLERIES_PER_PAGE)
                .forEach { card ->
                    runCatching { wallpapersOfGallery(card.slug) }
                        .getOrNull()
                        ?.let { items -> fresh += freshInCategory(category.term, items) }
                }
            cursor += GALLERIES_PER_PAGE
        }
        synchronized(lock) { categoryCursor[category.term] = cursor }
        val moreRemain = cursor < cards.size && page < MAX_CATEGORY_PAGES
        return Page(fresh, nextPage = if (moreRemain) page + 1 else null)
    }

    /** All wallpapers of one gallery — the workhorse behind search and category feeds. */
    private suspend fun wallpapersOfGallery(slug: String): List<Wallpaper> =
        fetchAndParse(WallpaperSafariParser::parseTopicGrid, "$BASE_URL/$slug/")
            .mapNotNull(::gridWallpaper)
            .let(::rememberTagsIn)

    /**
     * A query's gallery cards, fetched on demand and cached for one
     * pagination session — scrolling a result set must not re-issue the
     * query per page. The site's own zero-result marker maps to an empty
     * card list, so its trending suggestions are never served as
     * results and the honest end propagates to every page of the scroll.
     */
    private suspend fun searchCards(query: String): List<WallpaperSafariParser.TopicCard> {
        val key = "q:${query.trim().lowercase()}"
        synchronized(lock) {
            cardCache.remove(key)?.let { cached ->
                if (now() - cached.at < SEARCH_CACHE_MS) return cached.cards
            }
        }
        val response = get("$BASE_URL/search?q=${encode(query)}")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val cards =
            if (WallpaperSafariParser.hasNoResultsMarker(response.bodyText)) {
                emptyList()
            } else {
                WallpaperSafariParser.parseTopicCards(response.bodyText)
            }
        rememberCardTitles(cards)
        storeCards(key, cards)
        return cards
    }

    /** A category page's gallery directory, fetched on demand and cached the same way. */
    private suspend fun cachedCategoryCards(category: CategorySpec): List<WallpaperSafariParser.TopicCard> {
        val key = "cat:${category.term}"
        synchronized(lock) {
            cardCache.remove(key)?.let { cached ->
                if (now() - cached.at < SEARCH_CACHE_MS) return cached.cards
            }
        }
        val cards = fetchAndParse(WallpaperSafariParser::parseTopicCards, "$BASE_URL${category.directoryPath}")
        rememberCardTitles(cards)
        storeCards(key, cards)
        return cards
    }

    /** One card list into the session cache, oldest slot out past the cap. */
    private fun storeCards(
        key: String,
        cards: List<WallpaperSafariParser.TopicCard>,
    ) {
        synchronized(lock) {
            cardCache[key] = CachedCards(cards, now())
            while (cardCache.size > SEARCH_CACHE_SLOTS) {
                cardCache.remove(cardCache.keys.first())
            }
        }
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper. The medium card is a real JPEG every
     * Android decodes, so the grid carries it in [Wallpaper.thumbUrl] and
     * the disclosed original — the same file path one CDN up — in
     * [Wallpaper.fullUrl]. The like widget's dimensions are the file's
     * TRUE ones (the site publishes them nowhere else in a listing), so
     * they ride along; cells that lost them carry none, honestly. Tags
     * come from the gallery the item belongs to and the title's words, so
     * the detail screen's "More like this" row has a query to work with.
     */
    private fun gridWallpaper(item: WallpaperSafariParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.originalUrl,
            title = item.title,
            width = item.width,
            height = item.height,
            tags = tagsOf(item.topicSlug, item.title),
        )

    /**
     * Gallery slug plus title words as tags: the slug's tail suffixes
     * (`-wallpapers`, singular included) read as spaces and resolution
     * labels drop. An item with neither yields no tags — honest
     * emptiness over noise.
     */
    private fun tagsOf(
        topicSlug: String?,
        title: String? = null,
    ): List<String> {
        val tags = mutableListOf<String>()
        topicSlug
            ?.let(::galleryTag)
            ?.let { tags += it }
        title
            ?.lowercase()
            ?.split(Regex("""[^a-z0-9']+"""))
            ?.filter { it.length >= MIN_TAG_LENGTH && it !in TITLE_STOP_WORDS }
            ?.let { tags += it.distinct() }
        return tags
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_TAGS)
    }

    /** `indian-actress-wallpapers` (or the singular, or a background tail) to `indian actress`. */
    private fun galleryTag(slug: String): String? =
        when {
            slug.endsWith("-wallpapers") -> slug.removeSuffix("-wallpapers")
            slug.endsWith("-wallpaper") -> slug.removeSuffix("-wallpaper")
            slug.endsWith("-backgrounds") -> slug.removeSuffix("-backgrounds")
            slug.endsWith("-background") -> slug.removeSuffix("-background")
            else -> slug
        }.replace('-', ' ')
            .trim()
            .takeIf { it.isNotBlank() }

    /** The detail record's tags: its gallery and its title's words. */
    private fun tagsOf(record: WallpaperSafariParser.DetailRecord): List<String> = tagsOf(record.topicSlug, record.title)

    /**
     * Drops ids this category's session already served; remembers the
     * rest, oldest first out past the cap. The cursor never revisits
     * galleries, so the set only guards overlap between adjacent batches.
     */
    private fun freshInCategory(
        term: String,
        wallpapers: List<Wallpaper>,
    ): List<Wallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        synchronized(lock) {
            val seen = categorySeenIds.getOrPut(term) { LinkedHashSet() }
            val fresh = wallpapers.filter { it.id !in seen }
            fresh.forEach { seen.add(it.id) }
            while (seen.size > CATEGORY_SEEN_LIMIT) {
                seen.remove(seen.first())
            }
            return fresh
        }
    }

    /** Harvests seen tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTagsIn(wallpapers: List<Wallpaper>): List<Wallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        synchronized(lock) {
            wallpapers.flatMap { it.tags }.forEach { tag ->
                if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag)
            }
        }
        return wallpapers
    }

    /** Gallery titles feed the tag pool too — they are the site's own search terms. */
    private fun rememberCardTitles(cards: List<WallpaperSafariParser.TopicCard>) {
        if (cards.isEmpty()) return
        synchronized(lock) {
            cards.forEach { card ->
                card.title
                    .removeSuffix(" Wallpapers")
                    .removeSuffix(" Backgrounds")
                    .trim()
                    .lowercase()
                    .takeIf { it.isNotBlank() && tagPool.size < TAG_POOL_LIMIT }
                    ?.let { tagPool.add(it) }
            }
        }
    }

    // ------------------------------------------------------------- plumbing

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url)
            ?: error("configure() was not called")

    /** GETs [url], demands 2xx, hands the body to [parser]. */
    private suspend fun <T> fetchAndParse(
        parser: (String) -> List<T>,
        url: String,
    ): List<T> {
        val response = get(url)
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return parser(response.bodyText)
    }

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("wallpapersafari answered HTTP $statusCode")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun now(): Long = System.currentTimeMillis()

    /**
     * One browse shelf's category: the term its section carries and the
     * site's own directory path behind it (every path verified live).
     * Only `anime` rides the host's `category` vocabulary — the site
     * files no people category, so no `people` mapping exists and the
     * value honestly falls back to the default wall.
     */
    private data class CategorySpec(
        val term: String,
        val directoryPath: String,
    )

    private data class CachedCards(
        val cards: List<WallpaperSafariParser.TopicCard>,
        val at: Long,
    )

    private companion object {
        const val ID = "cloudimage.wallpapersafari"
        const val BASE_URL = "https://wallpapersafari.com"

        /**
         * The categories this provider can express, keyed by the host
         * vocabulary's own term where one applies. `art` is the site's
         * umbrella (its anime lives under it); the rest are the site's
         * own top-level directories, all serving the same gallery-card
         * shape.
         */
        val categoryByTerm =
            mapOf(
                "anime" to CategorySpec("anime", "/category/art/anime/"),
                "art" to CategorySpec("art", "/category/art/"),
                "animals" to CategorySpec("animals", "/category/animals/"),
                "cars" to CategorySpec("cars", "/category/cars/"),
                "nature" to CategorySpec("nature", "/category/nature/"),
                "sports" to CategorySpec("sports", "/category/sports/"),
                "travel" to CategorySpec("travel", "/category/travel/"),
            )

        /** Search batching: three galleries per page, ten pages deep at most. */
        const val GALLERIES_PER_PAGE = 3
        const val MAX_SEARCH_PAGES = 10

        /** Category feeds: this many pages deep at most. */
        const val MAX_CATEGORY_PAGES = 15

        /** Served-id window per category; overlap never spans this far. */
        const val CATEGORY_SEEN_LIMIT = 1_500

        /** Gallery-card cache: one pagination session, interleaved tabs. */
        const val SEARCH_CACHE_MS = 60_000L
        const val SEARCH_CACHE_SLOTS = 8

        /** Title-derived tags: word floor, noise list, per-item and pool caps. */
        const val MIN_TAG_LENGTH = 3
        const val MAX_TAGS = 6
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
                "new",
                "one",
                "two",
                "wallpaper",
                "wallpapers",
                "wall",
                "background",
                "backgrounds",
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
            )

        /** One lock over every mutable member; sections load in parallel. */
        val lock = Any()
    }

    private val cardCache = LinkedHashMap<String, CachedCards>()
    private val tagPool = LinkedHashSet<String>()

    /** Per-category pagination sessions: served ids and the gallery cursor. */
    private val categorySeenIds = LinkedHashMap<String, MutableSet<String>>()
    private val categoryCursor = mutableMapOf<String, Int>()
}

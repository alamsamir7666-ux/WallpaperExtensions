package com.cloudimage.behance

import com.cloudimage.provider.api.Album
import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Category
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
import java.util.Base64
import kotlin.random.Random

/**
 * Behance (https://www.behance.net) as a Cloudimage provider package — a
 * keyless reader of the world's largest creative-portfolio site, spoken the
 * way its own browser UI speaks it: no API, no key, no login, the
 * server-rendered pages it hands every anonymous visitor.
 *
 * ## Site model
 *
 * Behance is a portfolio of PROJECTS — a project is one designer's themed
 * image set ("Gradient Mesh Wallpapers", five images) with a cover, tags,
 * owners and true per-image dimensions. Both contract roles a Behance
 * project can play are wired:
 *
 * - **the flat feed** — the search wall `/search?search={term}` embeds its
 *   first 24 project cards in the page's application/json state (the
 *   Ember app's own preload), each card disclosing its cover in every
 *   rendition Behance generates: `404_webp` grid thumbs, `original_webp`
 *   full covers — a working wallpaper-grade URL straight off the grid,
 *   before the project page is ever fetched;
 * - **the album paradigm** — the same cards as albums (a project IS a
 *   titled collection of wallpapers), whose walls are the project page's
 *   image modules: each module discloses the `source` rendition — the
 *   uploaded ORIGINAL, often 3840 wide — with its TRUE dimensions, read
 *   from the module's own width/height.
 *
 * The search grammar, verified live against the SSR state:
 *
 * - `/search?search={term}` — Behance's own featured order (the honest
 *   popular feed: what the site itself serves first);
 * - `&sort=appreciations&time=all` — all-time most appreciated (the pair
 *   travels together; `sort` without `time` is ignored server-side);
 * - `&sort=published_date&time=all` — newest first (the Latest water);
 * - `&after={cursor}` — the wall's own cursor: base64 of the ordinal the
 *   next page starts at, `MjQ=` ("24") after the first 24 nodes, `NDg=`
 *   ("48") after the second — so page N's cursor is computable statelessly,
 *   exactly like the `/page/N` URL arithmetic of the other scrapers;
 * - a query the site cannot answer embeds an empty nodes array — an
 *   honest empty page, never suggestions dressed as results.
 *
 * The project page is keyed by the numeric id alone —
 * `/gallery/{id}/{slug}` serves the project whatever the slug says (the
 * site's own canonical redirects do the same), so grid ids stay short
 * numeric strings and details re-reads `/gallery/{id}/w`.
 *
 * ## The challenge dance
 *
 * behance.net sits behind a Varnish-edge JS challenge for non-browser
 * clients: a first GET answers HTTP 403 with a page whose only script sets
 * a `js_challenge_value` cookie and reloads. A plain HTTP client performs
 * the reload by hand — read the cookie out of the 403 body, present it on
 * the retry — and the page serves. The value is edge-fresh each time it is
 * refused (multiple cache nodes sit behind the load balancer), so the
 * dance is a loop: present the value from the immediately preceding 403,
 * up to [CHALLENGE_ATTEMPTS] quick attempts — live-verified, no sleeps.
 * A provider-supplied User-Agent rides the facade's sanctioned identity
 * swap; the challenge is not a Cloudflare challenge (no cf-mitigated
 * header, `server: Varnish`), so the host's bypass machinery correctly
 * never triggers and this stays the provider's own two-GET affair.
 *
 * ## How the contract maps onto it
 *
 * - [popular] is the featured search for the feed's own term — "wallpaper"
 *   by default — ridden through the host vocabulary: `sorting=toplist`
 *   switches the term's water to all-time most appreciated, `sorting=date`
 *   to newest first, and `category=anime`/`people` prefix their word onto
 *   the term (the site has no such buckets natively; the word rides the
 *   query, the same composed-terms trick the sections use).
 * - [search] is the site's own search of the user's query, as deep as the
 *   cursor walks, capped at [MAX_PAGES].
 * - [sections] offers thirteen shelves: the featured wall, the two sorted
 *   waters, and ten verified query presets the site answers precisely.
 * - [suggestTags] answers the tag rail the search state itself carries —
 *   Behance's own "wallpaper design", "wallpaper background" suggestions
 *   for the term already searched — harvested from fetched pages and kept
 *   in a small per-term cache, a bonus never a dependency: a fresh
 *   instance answers nothing, the host renders no suggestions.
 * - [details] re-reads the project page: the module-scoped full image
 *   (or the module the id names), TRUE dimensions, the project's own tags,
 *   its owners, its URL; a project without image modules (a video-only or
 *   prose-only project) degrades to its cover original rather than lying.
 * - the album paradigm: [categories] is a baked, offline-friendly shelf
 *   of curated terms; [homeAlbums] the featured wallpaper wall; [albums]
 *   a category's term searched; [searchAlbums] the user's query searched;
 *   [albumWallpapers] the project's image modules, the site's own order.
 * - [random] keeps the contract total with one random page of the
 *   featured wall — RANDOM is deliberately not declared, so the host
 *   never asks.
 *
 * ## Politeness
 *
 * The site's robots.txt keeps the generic-agent rule to `/v2/`, `/v3/`,
 * `/log` and one search-param variant — none of which this provider
 * touches; search and gallery pages stay open, and the AI-crawler blocks
 * do not speak to a user-driven client. Every fetch happens on an explicit
 * user action, one page per request, sequentially (plus the challenge
 * dance's anonymous GETs, byte-identical to a browser's reload), capped at
 * [MAX_PAGES] deep — no sitemap walking, no enumeration.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the only mutable state is the
 * per-term suggestion cache feeding [suggestTags] — a bonus, never a
 * dependency. One lock guards it; the host loads sections in parallel.
 */
class BehanceWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Behance",
            versionName = "1.0.0",
            author = "Cloudimage",
            description =
                "Wallpapers from the creative projects of behance.net - art wallpapers by the world's " +
                    "designers, with search, tags and project albums, keyless.",
            // Behance serves mature-flagged content behind its own safe
            // browsing switch (the state requests it on, showMature false),
            // and per-item rating metadata does not reach anonymous pages;
            // SFW is the honest floor, and the host's switch applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(
            Capability.POPULAR,
            Capability.LATEST,
            Capability.SEARCH,
            Capability.TAGS,
            Capability.FILTERS,
            Capability.ALBUMS,
        )

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    // -------------------------------------------------------------- feeds

    /**
     * The featured wall for the feed's own term, ridden through the host
     * vocabulary: `sorting` switches the water (toplist = all-time most
     * appreciated, date = newest first; anything else keeps the site's own
     * featured order), and `category=anime`/`people` prefix their word onto
     * the term. Category wins nothing over sorting — both compose.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> = runCatching { feedPage(FEED_TERM, filters, page) }

    /**
     * The site's own search of the user's query — one wall per request,
     * cursor-paginated exactly as the site paginates its own infinite
     * scroll, as deep as the site goes within the cap. A query the site
     * cannot answer embeds an empty nodes array — an honest empty page;
     * a blank query lands on the featured wall, the default feed.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                feedPage(FEED_TERM, filters, page)
            } else {
                feedPage(query, filters, page)
            }
        }

    /**
     * Suggestions ONLY from the tag rail the site itself ships with the
     * searched term's own state — Behance's "wallpaper design" rail, read
     * out of pages already being fetched and kept in a small per-term
     * cache so typing does not refetch. A fresh instance answers nothing;
     * a term whose page carried no rail answers nothing.
     */
    override suspend fun suggestTags(query: String): Result<List<String>> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching emptyList<String>()
            }
            synchronized(lock) { suggestionCache[query] } ?: run {
                feedPage(query, Filters.None, 1)
                synchronized(lock) { suggestionCache[query] }.orEmpty()
            }
        }

    /**
     * The shelves: the featured wall first, the two sorted waters, then
     * ten tag-style query presets the site's own search answers precisely.
     * Cheap and offline, as the contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "featured", title = "Featured"),
            HomeSection(id = "top", title = "Top Rated", filters = Filters.of("query" to FEED_TERM, "sorting" to "toplist")),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract wallpaper")),
            HomeSection(id = "4k", title = "4K", filters = Filters.of("query" to "4k wallpaper")),
            HomeSection(id = "phone", title = "Phone", filters = Filters.of("query" to "phone wallpaper")),
            HomeSection(id = "gradient", title = "Gradient", filters = Filters.of("query" to "gradient wallpaper")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space wallpaper")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("query" to "anime wallpaper")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature wallpaper")),
            HomeSection(id = "dark", title = "Dark", filters = Filters.of("query" to "dark wallpaper")),
            HomeSection(id = "minimal", title = "Minimal", filters = Filters.of("query" to "minimal wallpaper")),
            HomeSection(id = "aesthetic", title = "Aesthetic", filters = Filters.of("query" to "aesthetic wallpaper")),
        )

    /**
     * The definitive record for an id: the project page re-fetched and
     * re-read — the module the id names (or the project's first image),
     * the `source` rendition with its TRUE dimensions, the project's own
     * tags, its owner, the project URL. A project without image modules
     * degrades to its cover original; a page answering for another id, or
     * none at all, is a source failure — the grid item's own URLs already
     * work, so the host's viewer degrades gracefully.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val (projectId, moduleId) = splitId(id)
            val html = fetchPage("$GALLERY_BASE/$projectId/w")
            val record =
                BehanceParser.parseProject(html)
                    ?: error("unrecognized project page for '$id'")
            if (record.id != projectId) {
                error("project page answered for '${record.id}', not '$projectId'")
            }
            val image =
                moduleId
                    ?.let { wanted -> record.images.firstOrNull { it.moduleId == wanted } }
                    ?: record.images.firstOrNull()
                    ?: coverImageOf(record)
                    ?: error("project '$projectId' carries no images")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = image.thumbUrl,
                        fullUrl = image.fullUrl,
                        title = record.name.takeIf(String::isNotBlank),
                        width = image.width,
                        height = image.height,
                        tags = record.tags,
                    ),
                author = record.owners.firstOrNull(),
                resolution = resolutionOf(image.width, image.height),
                sourceUrl = record.url.takeIf(String::isNotBlank),
            )
        }

    /**
     * The site has no random endpoint of its own, so RANDOM is
     * deliberately not declared and the host never asks — this exists to
     * keep the contract total and answers one random page of the featured
     * wall, the honest shuffle of the feed that exists.
     */
    override suspend fun random(): Result<List<Wallpaper>> =
        runCatching { feedPage(FEED_TERM, Filters.None, Random.nextInt(RANDOM_DEPTH) + 1).wallpapers }

    // -------------------------------------------------------- the albums

    /**
     * The album sidebar: a baked, offline-friendly shelf of curated
     * wallpaper terms, each id doubling as the search term [albums] runs —
     * the same baked-fallback pattern the contract's category KDoc asks
     * for (the host asks once per album session).
     */
    override suspend fun categories(): List<Category> = bakedCategories

    /**
     * The album UI's single Home tab: the featured wallpaper wall itself —
     * the site's own answer to "show me wallpaper projects".
     */
    override suspend fun homeAlbums(): Result<List<Album>> = runCatching { cardsAsAlbums(fetchCards(FEED_TERM, Filters.None, 1)) }

    /** Every album of one category: the category's term, searched. */
    override suspend fun albums(categoryId: String): Result<List<Album>> =
        runCatching {
            if (categoryId.isBlank()) {
                return@runCatching emptyList()
            }
            cardsAsAlbums(fetchCards("$categoryId wallpaper", Filters.None, 1))
        }

    /** The user's query searched, album-style. */
    override suspend fun searchAlbums(query: String): Result<List<Album>> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching emptyList()
            }
            cardsAsAlbums(fetchCards(query, Filters.None, 1))
        }

    /**
     * The walls of one album: the project page re-read, every image
     * module a wallpaper — the `source` original with TRUE dimensions,
     * the site's own order, text and embed modules filtered out by the
     * parser's module filter. A project without image modules answers
     * its cover alone — the same honest degradation [details] documents.
     */
    override suspend fun albumWallpapers(albumId: String): Result<List<Wallpaper>> =
        runCatching {
            val (projectId, _) = splitId(albumId)
            val html = fetchPage("$GALLERY_BASE/$projectId/w")
            val record =
                BehanceParser.parseProject(html)
                    ?: error("unrecognized project page for '$albumId'")
            if (record.id != projectId) {
                error("project page answered for '${record.id}', not '$projectId'")
            }
            (if (record.images.isEmpty()) coverImageOf(record)?.let(::moduleAsList) else record.images)
                ?.map { image ->
                    Wallpaper(
                        id = "${record.id}-${image.moduleId}",
                        providerId = ID,
                        thumbUrl = image.thumbUrl,
                        fullUrl = image.fullUrl,
                        title = record.name.takeIf(String::isNotBlank),
                        width = image.width,
                        height = image.height,
                        tags = record.tags,
                    )
                }.orEmpty()
        }

    // ------------------------------------------------------------- pages

    /** One wall of the feed: fetch, parse, remember the tag rail, page. */
    private suspend fun feedPage(
        term: String,
        filters: Filters,
        page: Int,
    ): Page {
        val cards = fetchCards(term, filters, page) ?: return Page(emptyList(), nextPage = null)
        val more = if (cards.hasNextPage) page + 1 else null
        return Page(cards.cards.map(::cardWallpaper), nextPage = more)
    }

    private suspend fun fetchCards(
        term: String,
        filters: Filters,
        page: Int,
    ): SearchOutcome? {
        if (page < 1 || page > MAX_PAGES) return null
        val html = fetchPage(searchUrl(term, filters, page))
        val outcome =
            BehanceParser.parseSearch(html)
                ?: error("unrecognized search page for '${composedTerm(term, filters)}'")
        if (outcome.tagSuggestions.isNotEmpty()) {
            rememberSuggestions(term, outcome.tagSuggestions)
        }
        return outcome
    }

    /**
     * A search card into a flat-feed wallpaper: the 404-grade webp cover
     * for the grid, the cover ORIGINAL as the full — a working
     * wallpaper-grade URL before details are ever fetched. Dimensions are
     * deliberately absent: the card's cover renditions disclose none,
     * and a value that missing is worse than no value — [details] is
     * where TRUE dimensions arrive.
     */
    private fun cardWallpaper(card: ProjectCard): Wallpaper =
        Wallpaper(
            id = card.id,
            providerId = ID,
            thumbUrl = card.thumbUrl,
            fullUrl = card.fullUrl,
            title = card.name.takeIf(String::isNotBlank),
            contentRating = ContentRating.SFW,
        )

    private fun cardsAsAlbums(outcome: SearchOutcome?): List<Album> =
        outcome
            ?.cards
            ?.map { card ->
                Album(
                    id = card.id,
                    providerId = ID,
                    title = card.name.takeIf(String::isNotBlank) ?: "Untitled project",
                    coverUrl = card.thumbUrl,
                )
            }.orEmpty()

    /** The cover fallback of a module-less project, as a module image. */
    private fun coverImageOf(record: ProjectRecord): ModuleImage? {
        val full = record.coverFullUrl ?: return null
        return ModuleImage(
            moduleId = "",
            thumbUrl = record.coverThumbUrl ?: full,
            fullUrl = full,
            width = null,
            height = null,
        )
    }

    private fun moduleAsList(image: ModuleImage): List<ModuleImage> = listOf(image)

    // ------------------------------------------------------------- terms

    /**
     * The composed search term: the category word the host vocabulary
     * asks (anime, people) prefixes the term, so "wallpaper" becomes
     * "anime wallpaper" and a user's "naruto" becomes "anime naruto" —
     * the site has no category buckets, so the word rides the query.
     */
    private fun composedTerm(
        term: String,
        filters: Filters,
    ): String {
        val category =
            listOf("anime", "people")
                .firstOrNull { filters.isSelected("category", it) }
                ?: return term
        return "$category $term"
    }

    /**
     * The search URL: the term, the sorted water the host vocabulary asks,
     * and the computable cursor of the page. Visible for tests so the
     * grammar stays covered.
     */
    internal fun searchUrl(
        term: String,
        filters: Filters,
        page: Int,
    ): String {
        val builder = UrlBuilder("$BASE_URL/search")
        builder.param("search", composedTerm(term, filters))
        sortParams(filters).forEach { (name, value) -> builder.param(name, value) }
        if (page > 1) {
            builder.param("after", cursorOf(page))
        }
        return builder.build()
    }

    /**
     * The sorting translation: the host vocabulary's `toplist` is the
     * all-time most appreciated water and its `date` the newest-first
     * water — both SSR sorts need their `time` companion, verified live.
     * Anything else (the site's own featured order, and `random`, which
     * the site does not offer in search) keeps the featured water.
     */
    internal fun sortParams(filters: Filters): List<Pair<String, String>> =
        when (filters.valuesFor("sorting").firstOrNull()) {
            "toplist" -> listOf("sort" to "appreciations", "time" to "all")
            "date" -> listOf("sort" to "published_date", "time" to "all")
            else -> emptyList()
        }

    /**
     * The page-N cursor: base64 of the ordinal the page starts at — the
     * site's own pageInfo arithmetic (page 1 ends at `MjQ=` "24", page 2
     * at `NDg=` "48"), computable statelessly, exactly like the other
     * scrapers' page-URL arithmetic.
     */
    internal fun cursorOf(page: Int): String =
        Base64
            .getEncoder()
            .encodeToString((SSR_PAGE_SIZE * (page - 1)).toString().toByteArray())

    /**
     * The id grammar: a plain project id ("162276423") or a module-scoped
     * id ("162276423-915380531") — the album walls mint the scoped form
     * so details re-finds the exact image the user tapped.
     */
    private fun splitId(id: String): Pair<String, String?> {
        val trimmed = id.trim()
        require(trimmed.isNotEmpty()) { "id must not be blank" }
        val projectId = trimmed.substringBefore('-')
        val moduleId = trimmed.substringAfter('-', missingDelimiterValue = "")
        require(projectId.toLongOrNull() != null) { "id must name a Behance project id, was '$id'" }
        return projectId to moduleId.takeIf { it.toLongOrNull() != null }
    }

    /** `WxH`, or null when the module carried no dimensions. */
    private fun resolutionOf(
        width: Int?,
        height: Int?,
    ): String? =
        if (width != null && height != null) {
            "${width}x$height"
        } else {
            null
        }

    // -------------------------------------------------------------- fetch

    /**
     * GETs a page through the facade with the provider's browser identity,
     * performing the challenge dance when the edge answers 403: read the
     * fresh `js_challenge_value` out of the refusal, present it on the
     * immediate retry, repeat inside the attempt budget. Non-2xx answers
     * beyond the challenge, and challenges that never clear, are source
     * failures the host surfaces.
     */
    private suspend fun fetchPage(url: String): String {
        val client = httpClient ?: error("provider used before configure()")
        var cookie: String? = null
        repeat(CHALLENGE_ATTEMPTS) {
            val headers =
                buildMap {
                    put("User-Agent", BROWSER_UA)
                    cookie?.let { put("Cookie", it) }
                }
            val response = client.get(url, headers)
            when {
                response.isSuccessful -> return response.bodyText
                response.statusCode != CHALLENGE_STATUS ->
                    throw ProviderHttpException("behance.net answered HTTP ${response.statusCode}")
                else -> {
                    val value =
                        CHALLENGE_COOKIE.find(response.bodyText)?.groupValues?.get(1)
                            ?: throw ProviderHttpException(
                                "behance.net answered an unrecognized HTTP $CHALLENGE_STATUS page",
                            )
                    cookie = "js_challenge_value=$value"
                }
            }
        }
        throw ProviderHttpException(
            "behance.net did not clear its JavaScript challenge after $CHALLENGE_ATTEMPTS attempts",
        )
    }

    // -------------------------------------------------------------- state

    private val lock = Any()

    /** Per-term tag rails already fetched, feeding [suggestTags]; a bonus, never a dependency. */
    private val suggestionCache = LinkedHashMap<String, List<String>>()

    private fun rememberSuggestions(
        term: String,
        suggestions: List<String>,
    ) {
        synchronized(lock) {
            suggestionCache[term] = suggestions
            while (suggestionCache.size > SUGGESTION_CACHE_LIMIT) {
                suggestionCache.remove(suggestionCache.keys.first())
            }
        }
    }

    private companion object {
        const val ID = "cloudimage.behance"
        const val BASE_URL = "https://www.behance.net"
        const val GALLERY_BASE = "$BASE_URL/gallery"

        /** The featured feed's own term — the default water of the site's wallpaper world. */
        const val FEED_TERM = "wallpaper"

        /** The SSR wall size: 24 nodes per page, the site's own pageInfo arithmetic. */
        const val SSR_PAGE_SIZE = 24

        /** Deep-pagination cap: forty pages, twenty-four projects each. */
        const val MAX_PAGES = 40

        /** The random shuffle's honest depth within the featured wall. */
        const val RANDOM_DEPTH = 20

        /** The edge's JS challenge status and its cookie handoff. */
        const val CHALLENGE_STATUS = 403
        val CHALLENGE_COOKIE = Regex("""js_challenge_value=([a-f0-9]{32,})""")

        /**
         * One round per refusal observed; live pages have cleared anywhere
         * between one and twelve — the budget covers the edge's lottery.
         */
        const val CHALLENGE_ATTEMPTS = 12

        /** Per-term tag-rail cache cap. */
        const val SUGGESTION_CACHE_LIMIT = 16

        /**
         * The browser identity the facade swaps in for this provider's
         * requests — the sanctioned identity swap for scrapers; the
         * challenge itself is served regardless of agent, so this is
         * politeness, not bypass.
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * The baked category shelf of the album sidebar — curated terms,
         * each id doubling as the search term [albums] runs. Emoji badges
         * render in the host's tinted circles, the WallpaperAccess
         * pattern.
         */
        val bakedCategories =
            listOf(
                Category("abstract", "Abstract", "🔷"),
                Category("digital art", "Digital Art", "🎨"),
                Category("gradient", "Gradient", "🌈"),
                Category("space", "Space", "🌌"),
                Category("nature", "Nature", "🌿"),
                Category("anime", "Anime", "🌸"),
                Category("dark", "Dark", "🌑"),
                Category("neon", "Neon", "💡"),
                Category("minimal", "Minimal", "⬛"),
                Category("aesthetic", "Aesthetic", "✨"),
                Category("sci fi", "Sci-Fi", "🚀"),
                Category("landscape", "Landscape", "🏔"),
            )
    }
}

/** Minimal query-string builder — plugins have no HTTP library of their own. */
internal class UrlBuilder(
    private val base: String,
) {
    private val params = mutableListOf<Pair<String, String>>()

    fun param(
        name: String,
        value: String,
    ): UrlBuilder {
        params += name to value
        return this
    }

    fun build(): String =
        base +
            params.joinToString(prefix = "?", separator = "&") { (name, value) ->
                val encoded =
                    java.net.URLEncoder
                        .encode(value, Charsets.UTF_8.name())
                        .replace("+", "%20")
                        .replace("%2F", "/")
                        .replace("%3A", ":")
                "$name=$encoded"
            }
}

package com.cloudimage.zedge

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
 * Zedge (https://www.zedge.net) as a Cloudimage provider package —
 * the tenth keyless source in the repository set: no API, no key, the
 * site's own pages read the way its browser UI hydrates them.
 *
 * ## The flat directory (no album paradigm)
 *
 * Zedge is NOT an album site — there is no categories → albums →
 * wallpapers ladder anywhere in it. It is a flat directory of single
 * wallpapers behind stable UUID routes: every listing resolves
 * straight to wallpaper tiles, which is why this provider declares
 * the flat capability set (POPULAR, SEARCH, TAGS, FILTERS) and never
 * touches the album hooks ([WallpaperProvider.categories],
 * [homeAlbums], [albums], [albumWallpapers] stay defaulted — the
 * host never calls them without [Capability.ALBUMS]).
 *
 * - **The popular landing** (`/wallpapers`) — the site's own
 *   download-ranked grid, the honest POPULAR default.
 * - **Category pages** (`/category/wallpapers/{slug}`) — the site's
 *   25-category directory (animals, anime, nature, games, space …),
 *   each a grid of single wallpapers. The host vocabulary's `anime`
 *   and `people` ride them natively; the rest of the directory serves
 *   the home shelves below through the site's own search.
 * - **Search** (`/find/{query}`) — the site's own mixed-content
 *   search, scoped to its `WALLPAPER` section so ringtones,
 *   notification sounds, live wallpapers and artist profiles never
 *   leak in. A query the site answers with audio only parses to the
 *   honest zero, the same honest-empty rule every flat source follows.
 * - **Wallpaper pages** (`/wallpaper/{uuid}`) — the detail route the
 *   site's own sitemap system publishes by the thousand; the UUID is
 *   the wallpaper's stable id. The page's flight record discloses the
 *   title, the description, the tag row, the artist and the
 *   renditions.
 *
 * ## Reading the flight stream (v1.1.0)
 *
 * Zedge's grids are CLIENT-HYDRATED: the HTML its server sends carries
 * the app shell and a Next.js RSC flight stream — the actual
 * wallpapers live in that stream as `initialData` item records, not in
 * DOM anchors (there are none). [ZedgeParser] reads the stream — the
 * site's own SSR state, the same data its UI hydrates from — so every
 * listing and detail parses no matter which lane of the host's fetch
 * ladder delivered the document (the plain GET's raw HTML and the
 * WebView lane's rendered DOM both keep their script contents).
 *
 * ## Single-page listings (the honest answer)
 *
 * The grids' "Load more" is a client-side cursor XHR the URL cannot
 * express — the `?cursor=` form is ignored by the server (verified
 * live: the identical page re-served) — so every listing answers
 * exactly the one page the site itself renders and reports
 * `nextPage = null`. One page per request, the same politeness the
 * other keyless sources practice, and no duplicated items to fake
 * depth.
 *
 * ## Images — taken verbatim, never derived
 *
 * Every tile's thumb is the site's own grid crop (`thumbUrl`, a
 * signed `is.zobj.net` rendition), and the tile also carries the
 * site's bigger `previewUrl` as a working full before details are
 * ever fetched; details upgrade to the preview the wallpaper page
 * itself displays. The image host serves unchallenged — image bytes
 * never need the document lane. Nothing is derived or guessed — the
 * URLs the site's own browser UI loads are the ones this package
 * hands the host.
 *
 * ## The Cloudflare chapter (the host ladder owns it)
 *
 * zedge.net runs a Cloudflare managed challenge over every path for
 * datacenter egress. The provider code stays clean of any bypass
 * logic, per the repository doctrine: every fetch goes through the
 * plugin-facing HTTP facade, a non-2xx answer (the challenge's 403)
 * is reported as a [ProviderHttpException] and left to propagate —
 * in the app, the host's own clearance ladder (the engine document
 * lane, the wallpapersden interactive precedent) solves the challenge
 * once visibly and then rides the WebView silently for every
 * subsequent document fetch.
 *
 * ## Politeness
 *
 * The site's robots.txt disallows only `/user` and `/upload` (plus
 * named bad bots) — every route this package reads is open, and the
 * sitemap system (`/sitemap2/…`) is offered to crawlers openly. No
 * sitemap walking happens here: every fetch is an explicit user
 * action (a browse tab, a search keystroke submitted, a tap on a
 * tile), one page per request, sequential, exactly what one user
 * browsing zedge in a browser produces.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the only mutable state
 * is the seen-tag pool feeding [suggestTags] — a bonus seeded with
 * the site's own published category and tag terms (its landing
 * chips), refreshed by tags read off fetched detail pages, never a
 * dependency. A fresh instance answers identically, at worst with
 * suggestions alone. One lock guards it; the host loads sections in
 * parallel.
 *
 * ## Dimensions
 *
 * Neither the listing items nor the detail records state pixel
 * dimensions anywhere, so [Wallpaper.width]/[Wallpaper.height] and
 * [WallpaperDetails.resolution] stay honestly null rather than
 * invented — the host renders its placeholder, and the image bytes
 * themselves carry the truth for the viewer.
 */
class ZedgeWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Zedge",
            versionName = "1.1.0",
            author = "Cloudimage",
            description =
                "Free HD phone wallpapers from zedge.net - the flat directory of single " +
                    "wallpapers: popular grid, categories, tags and search, keyless.",
            // The site curates its gallery behind its own sensitive-content
            // gates; SFW is its own claim and the honest floor. The host's
            // rating switch still applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(
            Capability.POPULAR,
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
     * The default feed: the popular landing, ridden through the host
     * vocabulary — `category=anime` and `category=people` walk the
     * site's own category pages natively (`general` has no honest
     * expression — the site files no such category — so it falls back
     * to the landing, the contract's documented behavior for values
     * a provider cannot express). Sorting values are ignored: the
     * landing IS the site's download-ranked popular order, and no
     * other ranking is addressable. Single page — [ZedgeParser]'s
     * documentation explains why `nextPage` never lies about more.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val category = filters.valuesFor("category").firstNotNullOfOrNull(hostCategoryPath::get)
            val path = category ?: LANDING_PATH
            listingPage(path, page)
        }

    /**
     * The site's own search, scoped to its wallpapers section: one
     * request, the items of its `WALLPAPER` section — ringtones,
     * notification sounds, live wallpapers and artist profiles the
     * site may also answer are never served as wallpapers, and an
     * audio-only answer parses to the honest zero. A blank query
     * (the contract's escape hatch) lands on the popular landing's
     * first page, the same default the blank popular feed shows.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching listingPage(LANDING_PATH, 1)
            }
            if (page != 1) return@runCatching Page(emptyList(), nextPage = null)
            val html = fetchPage("$BASE_URL/find/${encode(query)}")
            Page(ZedgeParser.parseSearch(html).map(::gridWallpaper), nextPage = null)
        }

    /**
     * Suggestions from the pool of the site's own terms: the
     * published category and tag vocabulary baked in as the seed
     * (the landing's own chips), plus tags this instance has read
     * off fetched detail pages. No keystroke-driven scraping — a
     * prefix filter over the pool, capped, nothing more.
     */
    override suspend fun suggestTags(query: String): Result<List<String>> =
        runCatching {
            if (query.isBlank()) {
                emptyList()
            } else {
                synchronized(lock) { (bakedTags.asSequence() + tagPool.asSequence()).toList() }
                    .filter { it.contains(query, ignoreCase = true) }
                    .distinct()
                    .take(TAG_SUGGESTION_LIMIT)
            }
        }

    /**
     * The site's directory as shelves: the popular landing, the two
     * native host-vocabulary categories, then the site's own
     * category names as tag-style query presets its search addresses
     * precisely. Cheap and offline, as the contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "popular", title = "Popular"),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("category" to "anime")),
            HomeSection(id = "people", title = "People", filters = Filters.of("category" to "people")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space")),
            HomeSection(id = "sports", title = "Sports", filters = Filters.of("query" to "sports")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "cars")),
            HomeSection(id = "love", title = "Love", filters = Filters.of("query" to "love")),
            HomeSection(id = "music", title = "Music", filters = Filters.of("query" to "music")),
            HomeSection(id = "funny", title = "Funny", filters = Filters.of("query" to "funny")),
            HomeSection(
                id = "technology",
                title = "Technology",
                filters = Filters.of("query" to "technology"),
            ),
            HomeSection(id = "sayings", title = "Sayings", filters = Filters.of("query" to "sayings")),
        )

    /**
     * The definitive record for an id: the wallpaper page re-fetched
     * and re-read — the site's own title, its tag row, the artist it
     * credits, the grid crop as the thumb and the preview the page
     * itself displays as the full, the page's canonical share URL as
     * the source. A page that does not carry the record is a source
     * failure reported as such: the site's own "couldn't find it"
     * screen ships no record, and the grid item's own URLs already
     * work, so the host's viewer degrades gracefully. Transport
     * failures (the challenge's 403 among them) propagate untouched
     * for the host ladder to own.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val html = fetchPage(wallpaperUrlOf(id))
            val record =
                ZedgeParser.parseDetail(html)
                    ?: error("unrecognized wallpaper page for '$id'")
            rememberTags(record.tags)
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = record.thumbUrl,
                        fullUrl = record.fullUrl,
                        title = record.title,
                        tags = record.tags,
                    ),
                author = record.author,
                sourceUrl = record.shareUrl ?: wallpaperUrlOf(id),
            )
        }

    /**
     * The site has no random endpoint of its own, so RANDOM is
     * deliberately not declared and the host never asks — this
     * exists to keep the contract total and answers one shuffled
     * batch of the popular landing, the honest shuffle of the only
     * ranked feed the site serves.
     */
    override suspend fun random(): Result<List<Wallpaper>> =
        runCatching {
            val html = fetchPage("$BASE_URL$LANDING_PATH")
            ZedgeParser.parseGrid(html).map(::gridWallpaper).shuffled(Random.Default)
        }

    // ------------------------------------------------------------ streams

    /**
     * One listing page: fetch, parse the stream's tiles, the honest
     * `nextPage = null` every time (the site's "Load more" cursor is
     * a client XHR the URL cannot express — see the class
     * documentation). A page past the first answers the honest empty,
     * never a repeat of the first — and never a request either.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
    ): Page {
        if (page != 1) return Page(emptyList(), nextPage = null)
        val html = fetchPage("$BASE_URL$path")
        return Page(ZedgeParser.parseGrid(html).map(::gridWallpaper), nextPage = null)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A tile to a wallpaper: the thumb is the site's own grid crop,
     * and the full is the site's own bigger preview — a working
     * image of the same wallpaper before details are ever fetched;
     * [details] upgrades it to the page's own display rendition.
     * Titles ride the tile when the site states one.
     */
    private fun gridWallpaper(item: ZedgeParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.fullUrl,
            title = item.title,
        )

    /** Harvests seen detail tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTags(tags: List<String>) {
        if (tags.isEmpty()) return
        synchronized(lock) {
            tags.forEach { tag ->
                if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag)
            }
        }
    }

    // ------------------------------------------------------------ plumbing

    /** The wallpaper page of an id — the UUID route, encoded honestly. */
    private fun wallpaperUrlOf(id: String): String = "$BASE_URL/wallpaper/${encode(id)}"

    /** GETs a page, mapping non-2xx answers to source failures. */
    private suspend fun fetchPage(url: String): String {
        val client = httpClient ?: error("provider used before configure()")
        val response = client.get(url)
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return response.bodyText
    }

    /** URL-encoded for the site's paths — spaces as `%20`, the form its own routes speak. */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun httpError(status: Int): Exception = ProviderHttpException("zedge.net answered HTTP $status")

    // -------------------------------------------------------------- state

    private val lock = Any()

    /** Tags read off fetched detail pages, feeding [suggestTags]. */
    private val tagPool = LinkedHashSet<String>()

    private companion object {
        const val ID = "cloudimage.zedge"
        const val BASE_URL = "https://www.zedge.net"

        /** The popular landing — the site's download-ranked grid. */
        const val LANDING_PATH = "/wallpapers"

        /**
         * The host `category` vocabulary this provider can express:
         * the site's own anime and people category pages, natively.
         * `general` has no honest expression — the site files no
         * such category — so it is ignored and the landing serves,
         * the contract's documented behavior for values a provider
         * cannot express.
         */
        val hostCategoryPath =
            mapOf(
                "anime" to "/category/wallpapers/anime",
                "people" to "/category/wallpapers/people",
            )

        /**
         * The suggestion seed — the site's own published terms, its
         * landing's category and tag chips, all verified on the live
         * site. Suggestions are these plus whatever detail tags this
         * instance has seen.
         */
        val bakedTags =
            listOf(
                // The directory's categories.
                "animals",
                "anime",
                "bollywood",
                "brands",
                "cars",
                "comics",
                "designs",
                "drawings",
                "entertainment",
                "funny",
                "games",
                "holidays",
                "logos",
                "love",
                "music",
                "nature",
                "news",
                "pattern",
                "people",
                "sayings",
                "space",
                "spiritual",
                "sports",
                "technology",
                // The landing's own tag chips.
                "butterfly",
                "wolf",
                "cat",
                "dog",
                "gorilla",
                "panda",
                "leopard",
                "kuromi",
                "cinnamoroll",
                "itachi",
                "luffy",
                "sanrio",
                "my melody",
                "jdm",
                "hot wheels",
                "bmw",
                "bugatti",
                "zx10r",
                "overwatch",
                "league of legends",
                "valorant",
                "doom",
                "brawl stars",
                "counter strike",
                "csgo",
                "floral",
                "flower",
                "fall",
                "sage green",
                "great wall of china",
                // Search-verified live terms.
                "king wolf",
                "spirit wolf",
                "wolf howl",
                "halloween",
            )

        /** Tag pool and suggestion caps. */
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8
    }
}

package com.cloudimage.wallpaperaccess

import com.cloudimage.provider.api.Album
import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Category
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider
import java.net.URLEncoder

/**
 * WallpaperAccess (https://wallpaperaccess.com) as a Cloudimage provider
 * package — the repository's first ALBUM-STYLE source and the reason the
 * host grew its album paradigm (v1.1.0): no API, no key, the site's own
 * server-rendered pages read the way its browser UI does.
 *
 * ## Site model
 *
 * The site organizes content as categories -> albums -> wallpapers, the
 * hierarchy the album paradigm mirrors one-to-one:
 *
 * - 28 categories (`/cat/{slug}`), each shipping its own emoji in the
 *   navigation every page embeds (🌀 Abstract, 🐶 Animals, 🎮 Games …).
 *   A category page serves its COMPLETE album directory server-rendered —
 *   150 cards for anime — with title, slug, cover thumb and a wallpaper
 *   count, no pagination anywhere.
 * - Albums (`/{slug}`, e.g. /attack-on-titan) serve their COMPLETE
 *   wallpaper wall in one document (verified 70/70 and 99/99 live). Each
 *   item discloses its original (`/full/{id}.{ext}`) and its TRUE
 *   dimensions (`data-or`), and `/thumb/{id}.{ext}` answers a
 *   grid-sized copy — the extension must match the original's, a `.jpg`
 *   request for a PNG original is HTTP 415, so the extension always
 *   travels with the id.
 * - The homepage's collections segment is the site's own "newest albums"
 *   shelf — the album UI's single Home tab, and the flat popular feed's
 *   album stream.
 * - Search (`/search?q=`) answers ALBUMS, up to sixty, complete on one
 *   page. A query that matches nothing apologizes ("Sorry, no wallpapers
 *   found for …") above the homepage's trending cards — the marker is
 *   read so suggestions are never mistaken for results.
 *
 * ## Album paradigm mapping
 *
 * - [categories] answers the sidebar's list: the baked-in 28 as the
 *   offline floor, refreshed from the navigation of the first page this
 *   instance fetches — one homepage fetch when the session starts cold.
 * - [homeAlbums] is the homepage's own newest-albums shelf, complete.
 * - [albums] is one category's complete directory.
 * - [albumWallpapers] is one album's complete wall, re-pointed at
 *   `/thumb/` covers with `/full/` originals for apply/download.
 * - [searchAlbums] is the site's own search — albums the user drills
 *   into, exactly what its website shows.
 *
 * ## Flat-feed compatibility
 *
 * Older hosts (and the merged all-sources feed) know nothing of albums,
 * so the flat surfaces walk the same streams the album UI serves:
 * [popular] walks the homepage's newest albums three at a time, merging
 * their wallpapers; [search] walks the matching albums the same way. The
 * site has no latest or random endpoints and no host-vocabulary filter
 * dimensions its 28 categories could squeeze into, so LATEST, RANDOM and
 * FILTERS are deliberately not declared.
 *
 * ## Politeness
 *
 * robots.txt excludes `/download/` and `/search`. `/download/` is never
 * touched — the originals live at `/full/`, disclosed by the pages
 * themselves. `/search` carries the same deliberate, disclosed exception
 * wallpapercave, 4kwallpapers and wallpapersafari already document:
 * touched only on explicit user actions, one request per action plus one
 * per merged album, byte-identical to the site's own search box — never
 * crawling or enumerating. Fetches are sequential and capped (search ten
 * pages deep, the popular stream eight); the site sits behind Cloudflare,
 * and one-fetch-per-screen is gentler than the site's own browser traffic.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the mutable state is session
 * machinery only — the harvested category list, per-stream card caches
 * and the tag pool feeding [suggestTags] (a bonus, never a dependency).
 * A fresh instance answers identically, at worst without suggestions.
 * One lock guards every mutable member; the host loads sections in
 * parallel.
 */
class WallpaperAccessWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "WallpaperAccess",
            versionName = "1.0.1",
            author = "Cloudimage",
            description = "Album-organized wallpapers from wallpaperaccess.com - scraped, keyless.",
            // The site curates its albums and carries no per-item rating
            // metadata; SFW is its own claim and the honest floor. The
            // host's rating switch still applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.SEARCH, Capability.TAGS, Capability.ALBUMS)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    // ------------------------------------------------------------- albums

    /**
     * The sidebar's list: the baked-in navigation as the instant offline
     * floor, replaced by the live navigation on the first page this
     * instance fetches — a cold session fetches the homepage once for it
     * — and refreshed from every later fetch, since the menu rides along
     * every page the site serves. A failing refresh keeps the last known
     * list; only a never-connected instance answers the baked copy.
     */
    override suspend fun categories(): List<Category> {
        val cached =
            synchronized(lock) {
                if (categoriesHarvested) categoryList else null
            }
        if (cached != null) return cached
        refreshCategories()
        return synchronized(lock) { categoryList }
    }

    /** The homepage's own newest-albums shelf, complete — the Home tab. */
    override suspend fun homeAlbums(): Result<List<Album>> =
        runCatching {
            homeCards().map { it.toAlbum() }
        }

    /** One category's complete album directory, in the site's own order. */
    override suspend fun albums(categoryId: String): Result<List<Album>> =
        runCatching {
            categoryCards(categoryId).map { it.toAlbum() }
        }

    /**
     * One album's complete wall. The grid thumbs are re-pointed at
     * `/thumb/` (the site itself serves `/full/` originals as grid
     * images — a quarter of the bytes buys the same picture), originals
     * stay at `/full/` for apply/download, and every item carries its
     * TRUE dimensions from `data-or`. The id folds the album slug in
     * (`{slug}/{id}.{ext}`) so [details] can find the record again.
     */
    override suspend fun albumWallpapers(albumId: String): Result<List<Wallpaper>> =
        runCatching {
            wallpapersOfAlbum(albumId)
        }

    /**
     * The site's own search answer: albums, never loose wallpapers —
     * typing "naruto" lists the Naruto albums, exactly what the website
     * shows. The zero-result apology short-circuits to an honest empty
     * list; the trending cards below it are the site's suggestions and
     * are never served as answers.
     */
    override suspend fun searchAlbums(query: String): Result<List<Album>> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching homeCards().map { it.toAlbum() }
            }
            searchCards(query).map { it.toAlbum() }
        }

    // -------------------------------------------------------- flat feeds

    /**
     * The default flat feed: the homepage's newest albums walked three at
     * a time, each batch's walls fetched sequentially and merged — the
     * same stream the Home tab shows as albums, flattened for older hosts
     * and the merged all-sources feed. Honestly finished when the shelf
     * runs out; capped at eight pages.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            albumStreamPage(homeCards(), page, MAX_POPULAR_PAGES)
        }

    /**
     * The flat search: the site's matching albums walked three at a time,
     * their walls merged and deduped — the two-step CloudStream model the
     * other album-first source in this repository (wallpapersafari) uses.
     * The zero-result marker short-circuits to an honest empty page. A
     * blank query lands on the homepage shelf, the same default the
     * blank popular feed shows.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching albumStreamPage(homeCards(), 1, MAX_POPULAR_PAGES)
            }
            if (page < 1 || page > MAX_SEARCH_PAGES) {
                return@runCatching Page(emptyList(), nextPage = null)
            }
            val cards = searchCards(query)
            albumStreamPage(cards, page, MAX_SEARCH_PAGES)
        }

    /**
     * Suggestions come ONLY from album titles this instance has already
     * seen in fetched pages — no keystroke-driven scraping of the site's
     * search. A fresh instance answers nothing, which the host treats as
     * "no suggestions" rather than an error.
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
     * The definitive record for an id: the album page re-fetched and the
     * item re-read — TRUE dimensions, the cleaned alt title, the album
     * page URL. Transport failures propagate untouched, per the facade
     * contract; a non-2xx answer (deleted album, redesigned page) is a
     * source failure, reported as such.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val (slug, wallpaperId) = parseWallpaperId(id)
            val item =
                rawAlbumItems(slug).firstOrNull { it.id == wallpaperId }
                    ?: error("wallpaper '$wallpaperId' is not in album '$slug' anymore")
            WallpaperDetails(
                wallpaper = item.toWallpaper(slug),
                resolution = "${item.width}x${item.height}",
                sourceUrl = "$BASE_URL/$slug#$wallpaperId",
            )
        }

    /**
     * No random endpoint exists on the site and RANDOM is deliberately
     * not declared, so the host never asks — this exists to keep the
     * contract total and answers one random newest album's own batch.
     */
    override suspend fun random(): Result<List<Wallpaper>> =
        runCatching {
            val cards = homeCards()
            if (cards.isEmpty()) {
                emptyList()
            } else {
                wallpapersOfAlbum(cards.random().slug)
            }
        }

    // ------------------------------------------------------------ streams

    /**
     * One page of an album stream: the batch's albums fetched
     * sequentially, their walls merged and deduped by wallpaper id. A
     * failing album is skipped (its wallpapers, not the whole page).
     */
    private suspend fun albumStreamPage(
        cards: List<WallpaperAccessParser.AlbumCard>,
        page: Int,
        maxPages: Int,
    ): Page {
        if (page < 1 || page > maxPages) return Page(emptyList(), nextPage = null)
        val batch = cards.drop((page - 1) * ALBUMS_PER_FLAT_PAGE).take(ALBUMS_PER_FLAT_PAGE)
        if (batch.isEmpty()) return Page(emptyList(), nextPage = null)
        val wallpapers =
            batch
                .flatMap { card ->
                    runCatching { wallpapersOfAlbum(card.slug) }.getOrDefault(emptyList())
                }.distinctBy { it.id }
        val moreRemain = page * ALBUMS_PER_FLAT_PAGE < cards.size
        return Page(wallpapers, nextPage = if (moreRemain) page + 1 else null)
    }

    /** The homepage's newest-albums shelf, cached per session. */
    private suspend fun homeCards(): List<WallpaperAccessParser.AlbumCard> =
        synchronized(lock) { homeCardsCache } ?: run {
            val cards = parseCardsFrom(fetchPage("$BASE_URL/"))
            synchronized(lock) { homeCardsCache = cards }
            cards
        }

    /** One category's complete directory, cached per session. */
    private suspend fun categoryCards(categoryId: String): List<WallpaperAccessParser.AlbumCard> =
        synchronized(lock) { categoryCardsCache[categoryId] } ?: run {
            val cards = parseCardsFrom(fetchPage("$BASE_URL/cat/${encode(categoryId)}"))
            synchronized(lock) {
                categoryCardsCache[categoryId] = cards
            }
            cards
        }

    /** The search answer for one query, complete and cached per session — never the trending suggestions. */
    private suspend fun searchCards(query: String): List<WallpaperAccessParser.AlbumCard> =
        synchronized(lock) { searchCardsCache[query] } ?: run {
            val html = fetchPage("$BASE_URL/search?q=${encode(query)}")
            val cards =
                if (WallpaperAccessParser.hasNoResultsMarker(html)) {
                    emptyList()
                } else {
                    parseCardsFrom(html)
                }
            synchronized(lock) { searchCardsCache[query] = cards }
            cards
        }

    /**
     * One album's complete wall — fetched fresh every call; the host's
     * own back navigation caches, and album pages are the one-fetch unit
     * the whole paradigm is built on. The navigation menu rides along,
     * so the category list refreshes from here too.
     */
    private suspend fun wallpapersOfAlbum(slug: String): List<Wallpaper> = rawAlbumItems(slug).map { it.toWallpaper(slug) }

    /** Fetches and parses one album's raw item list, harvesting navigation and tags on the way. */
    private suspend fun rawAlbumItems(slug: String): List<WallpaperAccessParser.WallpaperItem> {
        val html = fetchPage("$BASE_URL/${encode(slug)}")
        harvest(html)
        return WallpaperAccessParser.parseWallpaperItems(html)
    }

    /** Parses cards off any page, harvesting categories and tags on the way. */
    private fun parseCardsFrom(html: String): List<WallpaperAccessParser.AlbumCard> {
        harvest(html)
        return WallpaperAccessParser.parseAlbumCards(html)
    }

    /** Refreshes the category list, cover urls and tag pool from any fetched page. */
    private fun harvest(html: String) {
        val links = WallpaperAccessParser.parseCategoryLinks(html)
        val cards = WallpaperAccessParser.parseAlbumCards(html)
        synchronized(lock) {
            if (links.isNotEmpty()) {
                categoryList = links.map { it.toCategory() }
                categoriesHarvested = true
            }
            cards.forEach { card ->
                tagPool += card.title
            }
        }
    }

    /** One homepage fetch purely to harvest the live category navigation. */
    private suspend fun refreshCategories() {
        runCatching { fetchPage("$BASE_URL/") }
            .onFailure {
                // The offline floor stands: the baked list keeps answering.
            }
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

    // -------------------------------------------------------------- types

    private fun WallpaperAccessParser.AlbumCard.toAlbum(): Album =
        Album(
            id = slug,
            providerId = ID,
            title = title,
            coverUrl = "$BASE_URL/thumb/$coverId.$coverExt",
            wallpaperCount = wallpaperCount,
        )

    private fun WallpaperAccessParser.WallpaperItem.toWallpaper(albumSlug: String): Wallpaper =
        Wallpaper(
            id = "$albumSlug/$id.$ext",
            providerId = ID,
            thumbUrl = "$BASE_URL/thumb/$id.$ext",
            fullUrl = "$BASE_URL/full/$id.$ext",
            title = title,
            width = width.takeIf { it > 0 },
            height = height.takeIf { it > 0 },
            tags = emptyList(),
        )

    /** Splits `{slug}/{id}.{ext}` — the id [details] routes on. */
    private fun parseWallpaperId(id: String): Pair<String, String> {
        val match = wallpaperIdPattern.find(id) ?: error("unrecognized wallpaper id '$id'")
        return match.groupValues[1] to match.groupValues[2]
    }

    private val wallpaperIdPattern = Regex("""^([a-z0-9-]+)/(\d+)\.\w+$""")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun httpError(status: Int): Exception = ProviderHttpException("wallpaperaccess answered HTTP $status")

    // -------------------------------------------------------------- state

    private val lock = Any()

    /** The sidebar's answer: the baked navigation until a fetch harvests the live one. */
    private var categoryList: List<Category> = bakedCategories()

    /** True once any fetch delivered the site's live navigation. */
    private var categoriesHarvested = false

    /** The homepage's newest-albums shelf, cached per session. */
    private var homeCardsCache: List<WallpaperAccessParser.AlbumCard>? = null

    /** Category directories by category id, cached per session. */
    private val categoryCardsCache = mutableMapOf<String, List<WallpaperAccessParser.AlbumCard>>()

    /** Search answers by query, cached per session — the flat search pages batch over them. */
    private val searchCardsCache = mutableMapOf<String, List<WallpaperAccessParser.AlbumCard>>()

    /** Album titles seen in fetched pages, feeding suggestTags. */
    private val tagPool = mutableSetOf<String>()

    private companion object {
        const val ID = "cloudimage.wallpaperaccess"
        const val BASE_URL = "https://wallpaperaccess.com"

        /** Albums per page of the flat popular/search streams. */
        const val ALBUMS_PER_FLAT_PAGE = 3

        /** The popular stream's honest depth: the homepage shelf is finite. */
        const val MAX_POPULAR_PAGES = 8

        /** The flat search's depth cap — the site serves at most sixty cards anyway. */
        const val MAX_SEARCH_PAGES = 10

        const val TAG_SUGGESTION_LIMIT = 8

        /**
         * The site's own navigation as of v1.0.1 — the offline floor the
         * sidebar answers before the first fetch lands, and the fallback
         * that keeps it alive when the network is down. The live menu
         * refreshes this on every session.
         */
        fun bakedCategories(): List<Category> =
            listOf(
                Category("abstract", "Abstract", "🌀"),
                Category("aesthetic", "Aesthetic", "🌻"),
                Category("animals", "Animals", "🐶"),
                Category("anime", "Anime", "💥"),
                Category("art", "Art", "🎨"),
                Category("bollywood", "Bollywood", "🤩"),
                Category("cars", "Cars", "🚗"),
                Category("celebrities", "Celebrities", "👥"),
                Category("city", "City", "🌆"),
                Category("colors", "Colors", "🌈"),
                Category("comics", "Comics", "🗯️"),
                Category("devices", "Devices", "📱"),
                Category("fantasy", "Fantasy", "🧚"),
                Category("flowers", "Flowers", "🌹"),
                Category("games", "Games", "🎮"),
                Category("holidays", "Holidays", "🎃"),
                Category("horror", "Horror", "🧟"),
                Category("love", "Love", "❤️"),
                Category("movies", "Movies", "🎞️"),
                Category("music", "Music", "🎤"),
                Category("nature", "Nature", "🌳"),
                Category("other", "Other", "➕"),
                Category("resolutions", "Resolutions", "↔️"),
                Category("space", "Space", "🚀"),
                Category("sport", "Sport", "🏀"),
                Category("textures", "Textures", "📜"),
                Category("travel", "Travel", "✈️"),
                Category("tv-shows", "TV Shows", "📺"),
            )
    }
}

package com.cloudimage.hdqwalls

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
 * HDQWalls (https://hdqwalls.com) as a Cloudimage provider package — the
 * second scraping source in the repository set: no API, no key, just the
 * site's own server-rendered pages read the way its browser UI does.
 *
 * ## Site model
 *
 * The site is listing-first and refreshingly uniform: popular
 * (`/popular-wallpapers`), latest (`/latest-wallpapers`), every category
 * (`/category/{slug}-wallpapers`) and search (`/search?q=`) serve the SAME
 * grid markup, eighteen items per page, with REAL pagination
 * (`/page/N`, `?page=N`) — a pagination bar whose `Next »` link is the
 * site's own "more exists" signal, absent on the last page. Thumbnails are
 * real JPEGs (602x339, the site's uniform card ratio) served from
 * `images.hdqwalls.com/wallpapers/bthumb/{file}.jpg`, and every one
 * discloses its multi-megabyte original by directory alone — the same
 * filename back under `/wallpapers/`. Wallpaper pages carry the file's
 * TRUE resolution, an author credit, a size label and the site's own tag
 * row.
 *
 * ## How the contract maps onto it
 *
 * - [popular] rides the host's filter vocabulary: a recognized `category`
 *   walks that category's listing (`anime` natively; `people` through the
 *   site's Celebrities category — the honest nearest expression of a
 *   vocabulary value the site has no equivalent of), `sorting=date` walks
 *   the latest feed, `sorting=random` serves one batch of the site's own
 *   random page, and everything else lands on the site's popular ranking —
 *   the default feed the browse tab shows first.
 * - [search] rides a four-tier chain, mirroring the site's own search
 *   page: the site's database first (one request per page, paginated as
 *   deep as the site itself goes, and its tag matching means a category
 *   name searched returns that category's content — verified live: `cars`
 *   — 12,303 results). When the database answers with NO grid cell — the
 *   exact condition under which the site's page embeds a Google
 *   Programmable Search Engine instead of results — the provider replays
 *   the site's fallback, reading that engine keylessly (bootstrap config
 *   from `cse.google.com/cse.js`, then the element API requested exactly
 *   as the site's own element requests it) — and the engine's answer
 *   splits by kind, because BOTH kinds are load-bearing. Its wallpaper
 *   pages (the singular `-wallpaper` slugs) resolve per result, each
 *   accepted only by its definitive record — the Original Resolution
 *   line — so the tag, category and search listings Google freely
 *   interleaves never masquerade as wallpapers. Its LISTING pages are
 *   followed instead of dropped: mined as ranked leads (Google's own
 *   answer to "which of the site's grids matches this query" — verified
 *   live: `hollywood actress` returns the `actress-wallpapers` tag seven
 *   times out of ten results), the winning listing's own deep pagination
 *   serves the query — 18 wallpapers a page, as deep as the site itself
 *   goes, deeper than Google's own ten-page cursor. When Google is
 *   unreachable (it rate-limits flagged networks), a per-word site search
 *   answers — the RICHEST word, not the longest: a word whose page
 *   continues (`actress`, 1,029 wallpapers) outranks a bigger dead end
 *   (`hollywood`, 2 wallpapers, no next page) — so a miss degrades to a
 *   deep stream of related results, never to a dead end.
 * - [sections] offers fourteen shelves: Popular, Latest, Anime (a host
 *   `category` preset), Celebrities (the `people` one), and tag-style
 *   `query` presets for Girls, Cars, Superheroes, Games, Movies, Nature,
 *   Abstract, Animals, Bikes and Sports — each rides the site's own
 *   search, which those terms address precisely.
 * - [details] fetches `/{id}` and reads the blockquote's `Original
 *   Resolution` line: the definitive URL, the file's true dimensions, the
 *   author, the download size and the site's own tags.
 * - [random] serves the site's `/random-wallpapers` batch as-is.
 *
 * ## Politeness
 *
 * The site's robots.txt excludes only `/ajax?s=` (the autocomplete this
 * provider never calls) and `/addauthor`. Every path used here — the
 * listings, the categories, the search, the wallpaper pages — is left
 * open. Fetches happen on explicit user actions, one request per page,
 * with a deep-pagination cap; a browser tab on the same pages costs the
 * same or more. The site blocks known-bot User-Agents but serves the
 * app's own identification (verified live against every path this
 * provider touches), so no browser impersonation is needed. The search
 * fallback's Google tier fires only after the site's own database
 * answered empty, and costs what the site's own embedded element costs:
 * one bootstrap fetch and one results call per fallback page — the
 * listing-lead tier then walks the site's own grid pages (two requests
 * per page once the leads are mined, cheaper than the ten eager page
 * fetches the direct-results tier would make), and the per-word tier
 * rides the same site pages as the primary search, a few single-word
 * requests at most.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the mutable state is two
 * bonuses, never dependencies. The tag pool feeding [suggestTags] and
 * the search fallback's query-keyed caches — the mined listing leads
 * and the anchor that served a query's earlier pages, keeping a
 * fallback stream stable while it scrolls — are both bounded, guarded
 * by one lock, and recomputed from the sources whenever a fresh
 * instance (or an evicted entry) needs them: a fresh instance answers
 * identically, at worst re-deriving what the cache would have remembered.
 * Sections load in parallel on the host side.
 *
 * ## Dimensions
 *
 * Grid items carry NONE, on purpose. The site's listing markup hard-codes
 * `width='602' height='339'` — its uniform card crop — on every single
 * cell, whatever the file's true size, so those attributes describe the
 * THUMBNAIL, never the wallpaper (a 3840x2159 original and a 1080x1920
 * portrait one both arrive labeled 602x339). Publishing them made the
 * app's info sheet claim "602x339" for every HDQWalls wallpaper — the bug
 * this version fixes. [details] is where the TRUE resolution arrives:
 * each wallpaper page's `Original Resolution` line, the only place the
 * site publishes it.
 */
class HdqWallsWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "HDQWalls",
            versionName = "1.0.4",
            author = "Cloudimage",
            description = "HD, 4K, 5K and 8K wallpapers from hdqwalls.com - scraped, keyless.",
            // The site curates its uploads and carries no per-item rating
            // metadata; SFW is its own claim and the honest floor. The
            // host's rating switch still applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.LATEST, Capability.SEARCH, Capability.FILTERS, Capability.TAGS, Capability.RANDOM)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    /**
     * The default feed, ridden through the host vocabulary: a recognized
     * `category` walks that category's listing, `sorting=date` walks the
     * latest feed, `sorting=random` serves one random batch (the site's
     * random page reshuffles on every load — there is no stable page 2),
     * and everything else lands on the site's popular ranking. Category
     * wins over sorting: it is the primary axis of the site's content.
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
                filters.isSelected("sorting", "random") -> Page(randomBatch(), nextPage = null)
                else -> listingPage(POPULAR_PATH, page)
            }
        }

    /**
     * The four-tier chain, the site's own search page replayed:
     *
     * 1. The site's database — one request per page, the same direct
     *    answer it has always given.
     * 2. A database MISS (zero grid cells — verified live for `indian
     *    actress`, a query whose pages exist but which the DB search
     *    cannot address) is the exact condition under which the site's
     *    page embeds Google Programmable Search instead. The provider
     *    reads that same engine keylessly: the bootstrap config, one
     *    element-API call in the element's own wire shape (`cse_tok`,
     *    JSONP callback and all) — and the results split by kind:
     *    LISTING leads first. The engine's answers for a broad query are
     *    dominated by the site's own grids (verified live: `hollywood
     *    actress` returns nine listing URLs out of ten, seven of them the
     *    `actress-wallpapers` tag), and the winning listing serves the
     *    query from its own deep pagination — 18 wallpapers a page, the
     *    site's own Next-bar cursor, past Google's ten-page element
     *    limit. The leads are mined once per query and cached, so page
     *    two of the search walks page two of the SAME listing — a stable
     *    stream, not a re-ranked one.
     * 3. Wallpaper-page results — the engine's direct hits, each accepted
     *    only as a hdqwalls wallpaper page resolved by its definitive
     *    record — the Original Resolution line — so Google's web results,
     *    which freely interleave tag and category listings with wallpaper
     *    pages, answer with real wallpapers only.
     * 4. Google unreachable (it rate-limits flagged networks with a 403
     *    apology page) or answerless: a per-word site search — the
     *    query's own stop-word-free words, up to three, the RICHEST
     *    answer winning: a page that continues (`actress`, 1,029
     *    wallpapers, 58 pages) outranks a bigger dead end (`hollywood`,
     *    2 wallpapers, no next page).
     *
     * Every tier returns a Page the next tier can continue: the listing
     * tier paginates by the site's own cursor, the CSE tier by result
     * offset, the per-word tier by the site's query-string pagination. A
     * blank query (the contract's escape hatch) lands on the latest
     * feed's first page, the same default the blank popular feed would
     * show.
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
            val own = listingPage("/search?q=${encode(query)}", page)
            if (own.wallpapers.isNotEmpty()) {
                return@runCatching own
            }
            searchFallback(query, page)
        }

    /**
     * Suggestions come ONLY from tags and title words this instance has
     * already seen in fetched pages — no keystroke-driven scraping of the
     * site's autocomplete (its `/ajax?s=` endpoint is the one path the
     * site's robots.txt excludes). A fresh instance answers nothing,
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
     * The site's shelves as tabs: the two ranked feeds first (Popular is
     * the default row the host's merged home picks), then the categories
     * in bar order. Anime rides the host's own `category` vocabulary and
     * Celebrities its `people` value; every other shelf is a tag-style
     * `query` preset the host routes through [search] with that term —
     * the site's search addresses each precisely. Cheap and offline, as
     * the contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "popular", title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("category" to "anime")),
            HomeSection(id = "girls", title = "Girls", filters = Filters.of("query" to "girls")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "cars")),
            HomeSection(id = "superheroes", title = "Superheroes", filters = Filters.of("query" to "superheroes")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "movies", title = "Movies", filters = Filters.of("query" to "movies")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "celebrities", title = "Celebrities", filters = Filters.of("category" to "people")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "bikes", title = "Bikes", filters = Filters.of("query" to "bikes")),
            HomeSection(id = "sports", title = "Sports", filters = Filters.of("query" to "sports")),
        )

    /**
     * The definitive record for an id: the page's blockquote carries the
     * original's exact URL and TRUE dimensions, the author credit, the
     * download size and the site's own tags. Transport failures propagate
     * untouched, per the facade contract; a non-2xx answer (deleted
     * wallpaper, redesigned page) is a source failure, reported as such —
     * the page slug alone discloses no image URL to degrade to (the
     * image filename carries a random suffix), and the host's viewer
     * already holds the grid item's own URLs.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/${encode(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record = HdqWallsParser.parseDetail(response.bodyText) ?: error("unrecognized wallpaper page for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = HdqWallsParser.toThumbUrl(record.originalUrl) ?: record.originalUrl,
                        fullUrl = record.originalUrl,
                        title = record.title,
                        width = record.width,
                        height = record.height,
                        tags = record.tags.take(MAX_TAGS),
                    ),
                author = record.author,
                resolution =
                    if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                fileSizeBytes = record.fileSizeBytes,
                sourceUrl = "$BASE_URL/$id",
            )
        }

    /** The site's own random page: one fresh batch of eighteen, no stable page 2. */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { randomBatch() }

    // ---------------------------------------------------------------- feed

    /**
     * Any listing page: fetch, parse the grid, read the pagination bar's
     * `Next »` link for what follows. The link is the site's own
     * "more exists" signal — past-the-end page requests clamp to the last
     * page's content on this site, so a blindly incremented cursor would
     * serve the final batch forever; the bar is the stopper. A
     * [MAX_PAGES] cap bounds the deepest scroll the same politeness way.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val url =
            if (path.startsWith("/search")) {
                "$BASE_URL$path${if (page > 1) "&page=$page" else ""}"
            } else if (page > 1) {
                "$BASE_URL$path/page/$page"
            } else {
                "$BASE_URL$path"
            }
        val response = get(url)
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val wallpapers =
            HdqWallsParser.parseGrid(response.bodyText).map(::gridWallpaper).let(::rememberTagsIn)
        return Page(wallpapers, nextPage = HdqWallsParser.parseNextPage(response.bodyText))
    }

    /** The random batch — same grid, no pagination signal worth reading. */
    private suspend fun randomBatch(): List<Wallpaper> {
        val response = get("$BASE_URL$RANDOM_PATH")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return HdqWallsParser.parseGrid(response.bodyText).map(::gridWallpaper).let(::rememberTagsIn)
    }

    // ------------------------------------------------------------ search fallback

    /**
     * The fallback tiers for a query the site's own database cannot
     * address — the listing-lead tier, the direct-results tier, and the
     * per-word tier, in that order, each degrading to the next on a miss.
     * One engine call feeds the first two: the element's FIRST page (start
     * 0) is where Google's ranking of the site's listings lives, so that
     * one answer is mined for leads and — when no lead serves — its
     * wallpaper-page results resolve directly, without a second fetch.
     */
    private suspend fun searchFallback(
        query: String,
        page: Int,
    ): Page {
        val key = query.trim().lowercase()
        val cachedLeads = synchronized(lock) { searchLeads[key] }
        // Mining is worth a Google round trip only inside the element's own
        // cursor depth; past it (a deep scroll into a cached lead stream)
        // the cached leads alone decide, exactly as before.
        val mined =
            if (cachedLeads != null || page < 1 || page > CSE_MAX_PAGES) {
                null
            } else {
                fetchCsePage(query, 0)
            }
        val leads =
            cachedLeads
                ?: mined?.let { rememberLeads(key, HdqWallsCseSearch.listingLeads(it.results)) }
        if (!leads.isNullOrEmpty()) {
            leadSearchPage(key, leads, page)?.let { return it }
        }
        val direct =
            if (page == 1 && mined != null) {
                resolveCseResults(mined)
            } else {
                cseSearchPage(query, page)
            }
        if (direct.wallpapers.isNotEmpty()) {
            return direct
        }
        return wordSearchPage(query, page) ?: Page(emptyList(), nextPage = null)
    }

    /**
     * The listing-lead tier: the ranked leads walked anchor-first — the
     * anchor being the lead that served this query's earlier pages (the
     * cache keeping the stream stable), or Google's top-ranked lead when
     * none has served yet. A lead that answers with wallpapers wins the
     * whole query, its OWN pagination intact: the site's 18-a-page grid,
     * Next-bar cursor and all, deeper than Google's ten-page element
     * cursor. A lead that fails — transport, HTTP, an empty grid — hands
     * the query to the next lead, never to an error; when every lead is
     * dead the mining is forgotten so the next page re-derives it.
     */
    private suspend fun leadSearchPage(
        key: String,
        leads: List<String>,
        page: Int,
    ): Page? {
        if (page < 1 || page > MAX_PAGES) return null
        val anchor = synchronized(lock) { searchAnchors[key] } ?: leads.first()
        val ordered = (listOf(anchor) + leads).distinct().take(LEAD_TRY_LIMIT)
        for (lead in ordered) {
            val candidate = runCatching { listingPage(lead, page) }.getOrNull() ?: continue
            if (candidate.wallpapers.isNotEmpty()) {
                rememberAnchor(key, lead)
                return candidate
            }
        }
        forgetSearch(key)
        return null
    }

    /**
     * One element-API page at [start], the shared fetch of the fallback
     * tiers: bootstrap config, then the results call in the element's own
     * wire shape. Null on any failure — token, transport, parse, Google's
     * rate-limit apology — the caller's tier degrades, never errors.
     */
    private suspend fun fetchCsePage(
        query: String,
        start: Int,
    ): HdqWallsCseSearch.CsePage? {
        val bootstrap =
            runCatching { get(HdqWallsCseSearch.BOOTSTRAP_URL) }.getOrNull()
                ?: return null
        if (!bootstrap.isSuccessful) return null
        val config = HdqWallsCseSearch.parseBootstrap(bootstrap.bodyText) ?: return null
        val response =
            runCatching {
                get(HdqWallsCseSearch.resultsUrl(encode(query), start, config))
            }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        return HdqWallsCseSearch.parseResults(response.bodyText, start)
    }

    /**
     * The Google tier for one page: the element's results at this page's
     * offset, each result resolved — its rich-snippet image when the
     * response volunteers a real site CDN original, its wallpaper page
     * otherwise, and only its definitive record at that. Sequential by
     * contract (the host exposes no dispatcher to plugin code), capped at
     * the page size. Any failure degrades to an empty page the chain
     * reads as "tier failed"; never an error.
     */
    private suspend fun cseSearchPage(
        query: String,
        page: Int,
    ): Page {
        if (page < 1 || page > CSE_MAX_PAGES) return Page(emptyList(), nextPage = null)
        val csePage =
            fetchCsePage(query, (page - 1) * HdqWallsCseSearch.PAGE_SIZE)
                ?: return Page(emptyList(), nextPage = null)
        return resolveCseResults(csePage)
    }

    /**
     * One engine page to a wallpaper page: every result resolved under
     * the two guards a Google web result needs before it can be trusted
     * as a wallpaper, the cursor's next offset read as the next page.
     * Empty results answer an empty page with NO next — the honest floor.
     */
    private suspend fun resolveCseResults(csePage: HdqWallsCseSearch.CsePage): Page {
        if (csePage.results.isEmpty()) return Page(emptyList(), nextPage = null)
        val wallpapers =
            csePage.results
                .take(CSE_RESOLVE_LIMIT)
                .mapNotNull { resolveCseResult(it) }
                .let(::rememberTagsIn)
        return Page(wallpapers, nextPage = csePage.nextStart?.let { it / HdqWallsCseSearch.PAGE_SIZE + 1 })
    }

    /**
     * One engine result to a wallpaper, under the two guards a Google
     * web result needs before it can be trusted as a wallpaper:
     *
     * - The slug: only the singular `-wallpaper` suffix addresses a
     *   wallpaper page — the site's tag, category and resolution listings
     *   all use the plural, and its search page no suffix at all, so the
     *   suffix is the page-kind discriminator. A listing result drops
     *   before any fetch fires.
     * - The record: a fetched page resolves only by its DEFINITIVE shape —
     *   the blockquote's `Original Resolution` line (the true dimensions
     *   the listing pages do not carry; their `og:image` volunteers a
     *   small `thumb/` crop, not an original). A snippet image still
     *   short-circuits the fetch when it is a real site CDN original —
     *   the bthumb/original shapes — which [HdqWallsParser.siteOriginalUrl]
     *   already verifies.
     *
     * Unresolvable results drop silently: a fallback page of three honest
     * wallpapers beats one padded with placeholders.
     */
    private suspend fun resolveCseResult(result: HdqWallsCseSearch.CseResult): Wallpaper? {
        val slug = HdqWallsCseSearch.pageSlug(result.pageUrl) ?: return null
        val title = HdqWallsCseSearch.cleanTitle(result.title)
        result.imageUrl?.let(HdqWallsParser::siteOriginalUrl)?.let { original ->
            return Wallpaper(
                id = slug,
                providerId = ID,
                thumbUrl = HdqWallsParser.toThumbUrl(original) ?: original,
                fullUrl = original,
                title = title.ifBlank { null },
                tags = tagsFromTitle(title),
            )
        }
        val response =
            runCatching { get("$BASE_URL/${encode(slug)}") }.getOrNull()
                ?: return null
        if (!response.isSuccessful) return null
        val record = HdqWallsParser.parseDetail(response.bodyText) ?: return null
        // The definitive record alone resolves: width arrives only from
        // the Original Resolution line, the one marker a wallpaper page
        // carries that no listing page does.
        if (record.width == null || record.height == null) return null
        return Wallpaper(
            id = slug,
            providerId = ID,
            thumbUrl = HdqWallsParser.toThumbUrl(record.originalUrl) ?: record.originalUrl,
            fullUrl = record.originalUrl,
            title = record.title ?: title.ifBlank { null },
            tags = record.tags.take(MAX_TAGS).ifEmpty { tagsFromTitle(title) },
        )
    }

    /**
     * The last-resort tier: the query's own words, searched one by one on
     * the site — stop words and resolution labels dropped (the same
     * vocabulary the title tags use), at most [SPLIT_WORD_LIMIT] words,
     * longest first (the most specific term wins: `actress` outranks
     * `indian`). The RICHEST answer wins, not the first: a page that
     * continues (the Next bar present — `actress`, 1,029 wallpapers, 58
     * pages) outranks a bigger dead end (`hollywood`, 2 wallpapers, no
     * next page), because the deeper stream is the one that keeps
     * serving the user's scroll. A full batch that continues wins on the
     * spot — no further word is asked. All words miss: null, and the
     * chain returns an honestly empty page.
     */
    private suspend fun wordSearchPage(
        query: String,
        page: Int,
    ): Page? {
        val words =
            query
                .lowercase()
                .split(Regex("""[^a-z0-9']+"""))
                .filter { it.length >= MIN_TAG_LENGTH && it !in TITLE_STOP_WORDS }
                .distinct()
                .sortedByDescending { it.length }
                .take(SPLIT_WORD_LIMIT)
        var best: Page? = null
        for (word in words) {
            // One word's transport or HTTP failure skips that word — the
            // tier's answer is the richest word that ANSWERED, and a
            // transient error on one must not fail the search.
            val candidate =
                runCatching { listingPage("/search?q=${encode(word)}", page) }.getOrNull()
                    ?: continue
            if (candidate.wallpapers.isEmpty()) continue
            if (candidate.nextPage != null && candidate.wallpapers.size >= FULL_PAGE_FLOOR) return candidate
            if (best == null || richer(candidate, best)) best = candidate
        }
        return best
    }

    /**
     * Whether [candidate] serves the query better than [best]: a page
     * that continues beats one that does not, and among two dead ends the
     * bigger batch wins.
     */
    private fun richer(
        candidate: Page,
        best: Page,
    ): Boolean =
        when {
            candidate.nextPage != null && best.nextPage == null -> true
            candidate.nextPage == null && best.nextPage == null && candidate.wallpapers.size > best.wallpapers.size -> true
            else -> false
        }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the thumbnail is a real JPEG every
     * Android decodes (unlike WallpaperCave's AVIF trap), so the grid
     * carries it in [Wallpaper.thumbUrl] and the disclosed original — the
     * same filename back under `/wallpapers/` — in [Wallpaper.fullUrl].
     * Dimensions are deliberately absent: the listing's `width`/`height`
     * attributes are the site's uniform 602x339 card crop, identical on
     * every cell and true of no wallpaper — a value that wrong is worse
     * than no value, and the app renders null dimensions as "—". Tags
     * derive from the title's words so the detail screen's "More like
     * this" row has a query to work with: the site's search matches
     * them, returning the same wallpaper family.
     */
    private fun gridWallpaper(item: HdqWallsParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.originalUrl,
            title = item.title.ifBlank { null },
            tags = tagsFromTitle(item.title),
        )

    /**
     * Title words as tags: lowercase, three characters or more, common
     * words and resolution labels dropped. A title without a usable word
     * yields no tags — honest emptiness over noise.
     */
    private fun tagsFromTitle(title: String): List<String> =
        title
            .lowercase()
            .split(Regex("""[^a-z0-9']+"""))
            .filter { it.length >= MIN_TAG_LENGTH && it !in TITLE_STOP_WORDS }
            .distinct()
            .take(MAX_TAGS)

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

    /**
     * The search fallback's caches — the mined leads and the serving
     * anchor, query-keyed, bounded and self-healing: a full cache clears
     * wholesale (the next search re-mines), a fresh instance starts
     * empty, and every entry is recomputable from the sources. The
     * remembered leads return as the caller's value — the cache is a
     * memo, not a second source of truth.
     */
    private fun rememberLeads(
        key: String,
        leads: List<String>,
    ): List<String> {
        synchronized(lock) {
            if (searchLeads.size >= SEARCH_CACHE_LIMIT) searchLeads.clear()
            searchLeads[key] = leads
        }
        return leads
    }

    /** Remembers the lead that served a query, keeping its stream stable. */
    private fun rememberAnchor(
        key: String,
        lead: String,
    ) {
        synchronized(lock) {
            if (searchAnchors.size >= SEARCH_CACHE_LIMIT) searchAnchors.clear()
            searchAnchors[key] = lead
        }
    }

    /** Forgets a query's mining — every lead proved dead; the next page re-derives. */
    private fun forgetSearch(key: String) {
        synchronized(lock) {
            searchLeads.remove(key)
            searchAnchors.remove(key)
        }
    }

    // ------------------------------------------------------------- plumbing

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url)
            ?: error("configure() was not called")

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("hdqwalls answered HTTP $statusCode")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val ID = "cloudimage.hdqwalls"
        const val BASE_URL = "https://hdqwalls.com"

        /** The ranked and time-ordered feeds; the random page behind [random]. */
        const val POPULAR_PATH = "/popular-wallpapers"
        const val LATEST_PATH = "/latest-wallpapers"
        const val RANDOM_PATH = "/random-wallpapers"

        /**
         * The host `category` vocabulary this provider can express: `anime`
         * natively, `people` through the site's Celebrities category — the
         * honest nearest expression of a value the site files no category
         * under. `general` needs no mapping: it is the default feed.
         */
        val hostCategoryPath =
            mapOf(
                "anime" to "/category/anime-wallpapers",
                "people" to "/category/celebrities-wallpapers",
            )

        /** Deep-pagination cap: a hundred pages, eighteen items each. */
        const val MAX_PAGES = 100

        /** The Google tier's page cap — the element cursor itself stops at ten. */
        const val CSE_MAX_PAGES = 10

        /** Result pages resolved eagerly per fallback page (the page size). */
        const val CSE_RESOLVE_LIMIT = 10

        /** The listing-lead tier's try cap: the anchor, then the next-ranked leads. */
        const val LEAD_TRY_LIMIT = 3

        /** The search caches' entry cap; a full cache clears wholesale. */
        const val SEARCH_CACHE_LIMIT = 8

        /** A full listing batch (18 live) with this much slack continues on the spot. */
        const val FULL_PAGE_FLOOR = 15

        /** The per-word tier's word cap: three tries, longest first. */
        const val SPLIT_WORD_LIMIT = 3

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
                "part",
                "new",
                "one",
                "two",
                "wallpaper",
                "wallpapers",
                "wall",
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

        /** One lock over the tag pool; sections load in parallel. */
        val lock = Any()
    }

    private val tagPool = LinkedHashSet<String>()

    /** The search fallback's query-keyed caches; see [rememberLeads]. */
    private val searchLeads = HashMap<String, List<String>>()
    private val searchAnchors = HashMap<String, String>()
}

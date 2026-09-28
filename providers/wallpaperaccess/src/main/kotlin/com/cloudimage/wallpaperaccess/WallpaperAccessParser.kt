package com.cloudimage.wallpaperaccess

/**
 * Hand-rolled HTML mining for wallpaperaccess.com — the scraping half of
 * this provider, kept free of any parsing library on purpose.
 *
 * Extension packages carry ONLY their own classes: the build dexes the
 * module jar and nothing else, so a dependency like Jsoup would be missing
 * at load time (the host supplies the contract and kotlinx.serialization,
 * nothing more). Everything below is therefore stdlib string and regex
 * work over the site's server-rendered markup — exactly the technique the
 * CloudStream extension ecosystem uses against page-embedded data.
 *
 * The site is a collection library: every listing — the popular ranking
 * (`/most-popular`), the fresh feed (`/new`) and every collection
 * (`/fall`, `/naruto`, `/anime`, …) — serves its ENTIRE inventory in one
 * server-rendered page, one `div[id="{wallpaperId}"]` per wallpaper, each
 * carrying the three attributes that matter: `data-fullimg` (the original
 * file under `/full/`), `data-or` (the file's TRUE dimensions — verified
 * pixel-exact against the served image), and, on the cell's `img`,
 * `data-slug` (the owning collection) and an `alt` whose leading
 * `WxH ` prefix precedes the display title. The same filename under
 * `/thumb/` is the site's lighter preview (verified live: a 1680x1050
 * original answers 600x400 there, fifteen-for-fifteen across six
 * listings). There is no pagination anywhere — `?page=2` serves the
 * identical page — so a listing's batch IS its whole inventory, and the
 * endless scroll continues through OTHER collections: themed roots
 * (the tab presets, the host categories, search hits) walk the
 * same-theme siblings the site's own public sitemap enumerates, while
 * the two ranked mixed feeds continue through the Related Wallpapers
 * band every listing ends with — the site's own "keep browsing"
 * recommendations, mixed-theme by design and honest there. Unknown
 * collections answer a clean 404 whose title says
 * "Page not found".
 *
 * Readers below key on those markers rather than document order:
 * attribute order is never assumed (the site has already been observed
 * serving `src` on the first cell's img and only `data-src` on the rest,
 * and the `data-download` value spans a newline), and cells are parsed
 * slice by slice so a malformed cell cannot swallow its neighbors. All
 * functions are pure and total: bad input yields empty lists and nulls,
 * never exceptions — callers decide what a miss means.
 */
internal object WallpaperAccessParser {
    /** One grid cell: a wallpaper with its original, dimensions and title. */
    data class GridItem(
        /** The wallpaper's numeric id — the cell div's own `id`. */
        val numericId: String,
        /** The original's file name, e.g. `343386.jpg` — id plus extension. */
        val fileName: String,
        /** The file's TRUE width, from `data-or`; null when absent. */
        val width: Int?,
        /** The file's TRUE height, from `data-or`; null when absent. */
        val height: Int?,
        /** Display title — the img `alt` minus its leading `WxH ` prefix. */
        val title: String?,
        /** The owning collection, the img's `data-slug`; null when absent. */
        val slug: String?,
    )

    /** Attribute splitter: `name="value"` and `name='value'` pairs, any order. */
    private val ATTR =
        Regex(
            """([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        )

    /** Any `<div …>` tag; the cell root is found by its attributes. */
    private val ANY_DIV = Regex("""<div\b[^>]*>""")

    /** Any `<img …>` tag. */
    private val ANY_IMG = Regex("""<img\b[^>]*>""")

    /** The original's path shape: `/full/{id}.{ext}`. */
    private val FULL_FILE = Regex("""^/full/(\d+)\.([a-zA-Z0-9]+)$""")

    /** The `data-or` shape: `{width}x{height}`, digits only. */
    private val DIMS = Regex("""^(\d+)x(\d+)$""")

    /** The img `alt`'s leading dimensions prefix: `1680x1050 `. */
    private val ALT_DIMS_PREFIX = Regex("""^\d+x\d+\s+""")

    /**
     * The grid of any listing page — popular, fresh, a collection, or a
     * slug-guessed search address: the site serves one shared cell shape
     * for all of them. The cell roots are the `div`s whose attributes
     * carry a purely numeric `id` (verified live: no other div family on
     * any listing page qualifies — ad containers and layout scaffolding
     * all carry word ids); each cell's chunk runs from its own root to
     * the next one, so a malformed cell cannot swallow its neighbors, and
     * the last cell's chunk safely absorbs the trailing related-collections
     * band because those cards carry no `data-fullimg` and no `data-id`
     * img. Items dedupe by numeric id as insurance.
     */
    fun parseGrid(html: String): List<GridItem> {
        val starts =
            ANY_DIV
                .findAll(html)
                .mapNotNull { match ->
                    val id = attributes(match.value)["id"].orEmpty()
                    if (id.isNotEmpty() && id.all(Char::isDigit)) match.range.first to id else null
                }.toList()
        if (starts.isEmpty()) return emptyList()
        return starts
            .indices
            .mapNotNull { i ->
                val (from, numericId) = starts[i]
                val to = if (i + 1 < starts.size) starts[i + 1].first else html.length
                parseCell(numericId, html.substring(from, to))
            }.toList()
            .distinctBy { it.numericId }
    }

    /**
     * One cell: the root div's own attributes carry `data-fullimg` (the
     * original) and `data-or` (the TRUE dimensions); the cell's img —
     * the first one carrying `data-id`, wherever it sits, whatever its
     * loading attributes — carries the `alt` title and `data-slug`. A
     * cell without a recognizable `/full/` original drops.
     */
    private fun parseCell(
        numericId: String,
        cell: String,
    ): GridItem? {
        val root = ANY_DIV.find(cell)?.value ?: return null
        val attrs = attributes(root)
        val original = FULL_FILE.find(attrs["data-fullimg"].orEmpty()) ?: return null
        if (original.groupValues[1] != numericId) return null
        val dims = attrs["data-or"]?.let { DIMS.find(it) }
        val img =
            ANY_IMG
                .findAll(cell)
                .firstOrNull { tag -> attributes(tag.value)["data-id"] != null }
                ?.let { attributes(it.value) }
                .orEmpty()
        val title =
            img["alt"]
                ?.let { unescapeEntities(it).replaceFirst(ALT_DIMS_PREFIX, "").trim() }
                ?.takeIf { it.isNotEmpty() }
        return GridItem(
            numericId = numericId,
            fileName = "${original.groupValues[1]}.${original.groupValues[2]}",
            width = dims?.groupValues?.get(1)?.toIntOrNull(),
            height = dims?.groupValues?.get(2)?.toIntOrNull(),
            title = title,
            slug = img["data-slug"]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The Related Wallpapers band of a listing page — the site's own
     * "keep browsing" recommendations, served under an
     * `<h2 id="related">` heading after the grid. Each card is one anchor
     * to a sibling collection's slug address, and the band runs to the
     * how-to heading that follows it; live pages carry nine cards, though
     * the count is the site's choice, not a promise. This is the surface
     * the two RANKED mixed feeds (popular, fresh) ride on deeper pages:
     * those feeds are cross-theme by nature, so the site's mixed
     * recommendations are an honest continuation for them. Themed roots
     * never touch this band — its cards name whatever the site cares to
     * recommend (the live nature band runs ocean, summer, spring, easter,
     * galaxy, technology, windows, car, winter-nature), which is exactly
     * how a themed tab would come to serve off-theme wallpapers.
     *
     * Pruning keeps any walk honest: the root's own slug drops (a
     * self-link would repeat the batch), non-collection addresses drop
     * (`about`, `faq`, … — never seen live, but a stray nav anchor inside
     * the window must not poison the walk), and duplicates keep their
     * first position. Bad input — no band, an empty band — yields an
     * empty list, and the feed simply ends where the site ends it.
     */
    fun parseRelated(
        html: String,
        self: String,
    ): List<String> {
        val heading = html.indexOf(RELATED_HEADING)
        if (heading < 0) return emptyList()
        val from = heading + RELATED_HEADING.length
        // The band's natural end is the how-to heading; a page without one
        // falls back to a bounded window so a missing boundary can never
        // drag the rest of the document in.
        val nextHeading = html.indexOf("<h2", from)
        val to =
            if (nextHeading > from) {
                nextHeading
            } else {
                minOf(html.length, from + RELATED_WINDOW)
            }
        return RELATED_LINK
            .findAll(html.substring(from, to))
            .mapNotNull { match ->
                val slug = match.groupValues[1]
                slug.takeIf { it != self && it !in NON_COLLECTION_SLUGS }
            }.toList()
            .distinct()
    }

    /** The Related Wallpapers heading marker, as the site serves it. */
    private const val RELATED_HEADING = "id=\"related\""

    /** A related card's anchor: `href="/slug"`, any attribute order. */
    private val RELATED_LINK = Regex("<a\\b[^>]*\\shref=\"/([a-z0-9]+(?:-[a-z0-9]+)*)\"")

    /**
     * The band's fallback window when no following heading bounds it —
     * comfortably past the ~10KB the live bands span, far short of the
     * rest of the document.
     */
    private const val RELATED_WINDOW = 20_000

    /**
     * Addresses that are pages of the site but never collections; a
     * related band or sitemap pointing at one (never seen live in the
     * band) would be skipped rather than walked. The ranked feeds
     * (`most-popular`, `new`) are deliberately absent: they are real
     * listings, and a related band recommending one is walked like any
     * other card.
     */
    private val NON_COLLECTION_SLUGS =
        setOf(
            "about",
            "contact",
            "faq",
            "terms",
            "dmca",
            "privacy-policy",
            "download",
            "full",
            "thumb",
            "search",
            "sitemap",
            "sitemap.xml",
        )

    // ------------------------------------------------------- the theme walk

    /**
     * The site's public sitemap — `/sitemap.xml`, a rolling window of
     * the newest ~5000 collections (verified live: the classic roots
     * — `nature`, `anime`, the ranked feeds — are NOT in it; their
     * siblings are). This is the theme walk's sibling source, the one
     * surface where the site enumerates collection addresses in bulk,
     * published precisely for automated readers and fetched once per
     * provider instance. Only the site's own collection shape survives:
     * single-segment paths on this host, minus the non-collection
     * addresses, deduped in served order. Bad input — a challenge page,
     * a truncated body — parses to zero slugs and the themed feeds end
     * honestly after their root batches.
     */
    fun parseSitemap(xml: String): List<String> =
        SITEMAP_LOC
            .findAll(xml)
            .mapNotNull { match ->
                match.groupValues[1]
                    .substringBefore('?')
                    .substringBefore('#')
                    .trim('/')
                    .takeIf { slug -> slug.isNotEmpty() && '/' !in slug && slug !in NON_COLLECTION_SLUGS }
            }.toList()
            .distinct()

    /** A sitemap `<loc>` on this host: the path after it, up to the tag's end. */
    private val SITEMAP_LOC = Regex("""<loc>\s*https://wallpaperaccess\.com/([^<]*)</loc>""")

    /**
     * The theme words a listing address names — the tokens that remain
     * after the generic vocabulary drops: English stopwords, resolution
     * and format words (`4k`, `hd`), device words (`phone`, `monitor`),
     * the site's own nouns (`wallpaper`, `background`), and anything
     * carrying a digit (overwhelmingly resolutions on this site).
     * `4k-gaming` names `gaming`; `nature` names `nature`; `4k-wallpapers`
     * names nothing — a query that cannot name a theme cannot walk one,
     * and its feed ends after the root batch.
     */
    fun themeTokensOf(slug: String): List<String> =
        slug
            .split('-')
            .filter { it.isNotEmpty() && it !in GENERIC_TOKENS && it.none(Char::isDigit) }
            .distinct()

    /**
     * Whether a candidate collection belongs to the theme those tokens
     * name — token-boundary equality, never substring: `fall` matches
     * `fall-leaves` but never `waterfall`, `car` never `card`. Plural
     * shapes flex both ways (`car`/`cars`, `movie`/`movies`) — the one
     * English morphology this vocabulary needs.
     */
    fun matchesTheme(
        candidateSlug: String,
        tokens: List<String>,
    ): Boolean {
        if (tokens.isEmpty()) return false
        val candidate = candidateSlug.split('-').toSet()
        return tokens.any { token ->
            token in candidate ||
                "${token}s" in candidate ||
                (token.length >= 4 && token.dropLast(1) in candidate)
        }
    }

    /**
     * Tokens that never name a theme — calibrated against the live
     * sitemap's most common tokens. Colors stay OUT of this list:
     * `dark`, `black`, `pink` name real themes this site's users browse
     * by.
     */
    private val GENERIC_TOKENS =
        setOf(
            // English stopwords the site's slugs carry in volume.
            "the",
            "a",
            "an",
            "and",
            "or",
            "of",
            "in",
            "on",
            "at",
            "to",
            "for",
            "with",
            "from",
            "by",
            "my",
            "your",
            // Resolution and format words.
            "4k",
            "5k",
            "8k",
            "10k",
            "2k",
            "hd",
            "fullhd",
            "uhd",
            "qhd",
            "wqhd",
            "3d",
            // What the site serves, not what a collection is about.
            "wallpaper",
            "wallpapers",
            "background",
            "backgrounds",
            "image",
            "images",
            "pic",
            "pics",
            "photo",
            "photos",
            "art",
            "logo",
            // Devices and surfaces.
            "phone",
            "mobile",
            "desktop",
            "pc",
            "computer",
            "laptop",
            "monitor",
            "monitors",
            "dual",
            "screen",
            "screens",
            "amoled",
            "iphone",
            "android",
            "lockscreen",
            "lockscreens",
            "home",
            "homescreen",
        )

    /**
     * A free-text query to the site's collection-address shape: lowercase,
     * every non-alphanumeric run collapsed to one hyphen, edges stripped.
     * `4K Gaming!` becomes `4k-gaming`; decoration-only input collapses to
     * the empty string, which callers treat as a blank query. This is the
     * robots-compliant search: the site's own `/search` is excluded by its
     * robots.txt, but every collection page is served at its slug address,
     * and the site's own navigation links collections exactly this way.
     */
    fun slugify(query: String): String =
        query
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    /**
     * The entities the site's markup actually uses: the five XML names,
     * `&nbsp;`, and arbitrary numeric references (decimal and hex). `&amp;`
     * resolves last so pre-escaped text stays single-escaped.
     */
    fun unescapeEntities(text: String): String {
        if ('&' !in text) return text
        var out = text
        out =
            out.replace(Regex("""&#x([0-9a-fA-F]+);""")) {
                it.groupValues[1]
                    .toInt(16)
                    .toChar()
                    .toString()
            }
        out =
            out.replace(Regex("""&#(\d+);""")) {
                it.groupValues[1]
                    .toInt()
                    .toChar()
                    .toString()
            }
        out =
            out
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
        return out.replace("&amp;", "&")
    }

    /** Splits a tag's attributes (either quote style) into a lookup map. */
    private fun attributes(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { match ->
            match.groupValues[1] to match.groupValues[2].ifEmpty { match.groupValues[3] }
        }
}

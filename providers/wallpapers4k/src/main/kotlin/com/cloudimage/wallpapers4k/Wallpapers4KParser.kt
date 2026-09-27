package com.cloudimage.wallpapers4k

/**
 * Hand-rolled HTML mining for 4kwallpapers.com — the scraping half of this
 * provider, kept free of any parsing library on purpose.
 *
 * Extension packages carry ONLY their own classes: the build dexes the module
 * jar and nothing else, so a dependency like Jsoup would be missing at load
 * time (the host supplies the contract and kotlinx.serialization, nothing
 * more). Everything below is therefore stdlib string and regex work over the
 * site's server-rendered markup — exactly the technique the CloudStream
 * extension ecosystem uses against page-embedded data.
 *
 * The site speaks schema.org everywhere, which makes the anchors unusually
 * stable: every listing grid — the homepage's trending feed, the popular
 * ranking, every category, search results — serves the SAME
 * `p.wallpapers__item` cell carrying an `itemprop="keywords"` meta (the
 * title and the site's own tags), an `itemprop="contentUrl"` link (the
 * 800-wide preview) and an anchor to the wallpaper's page. Detail pages add
 * a `Download Original (WxH)` link — the only place the TRUE resolution is
 * published. Every reader below keys on those semantic markers rather than
 * document order, so cosmetic redesigns degrade parsing to "nothing found"
 * instead of producing garbage. Attribute order is never assumed (the site
 * has already been observed serving `title` before `href` in some cells and
 * after it in others). All functions are pure and total: bad input yields
 * empty lists and nulls, never exceptions — callers decide what a miss means.
 */
internal object Wallpapers4KParser {
    /** One grid cell: a wallpaper page link with its preview and tags. */
    data class GridItem(
        /**
         * The wallpaper's page path, e.g. `abstract/xiaomi-18-fold-27263.html`
         * — the provider's stable id and the [details] address at once.
         */
        val id: String,
        /** Absolute preview URL (`…/images/walls/thumbs_2t/{id}.jpg`, 800px). */
        val thumbUrl: String,
        /**
         * Absolute original URL (`…/images/walls/orig/{id}.jpg`) — the
         * multi-megabyte source file, disclosed by directory alone.
         */
        val originalUrl: String,
        /** Display title — the keywords meta's first segment. */
        val title: String,
        /** The site's own tags for the item — the keywords meta's remainder. */
        val tags: List<String>,
        // No width/height here, on purpose: the listing markup hard-codes
        // `width="400" height="225"` — the site's uniform 16:9 card crop —
        // on EVERY cell, whatever the file's true size, so those attributes
        // describe the THUMBNAIL, never the wallpaper (a 5120x2880 original
        // and a 4000x4000 square one both arrive labeled 400x225). The
        // file's TRUE dimensions exist only on the wallpaper's own page, in
        // the `Download Original (WxH)` link [parseDetail] reads. Grid items
        // therefore carry no dimensions at all — honest emptiness over a
        // number that is wrong for every wallpaper.
    )

    /** The definitive record of a wallpaper page. */
    data class DetailRecord(
        /** The site's own `Download Original` URL — the labeled original file. */
        val originalUrl: String,
        /** The file's TRUE dimensions, the only place the site publishes them. */
        val width: Int?,
        val height: Int?,
        /** Absolute preview URL for the record (the page's contentUrl image). */
        val thumbUrl: String?,
        /** Display title — the page-level keywords meta's first segment. */
        val title: String?,
        /** The site's own tags — the page-level keywords meta's remainder. */
        val tags: List<String>,
    )

    /** Attribute splitter: `name="value"` and `name='value'` pairs, any order. */
    private val ATTR =
        Regex(
            """([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        )

    /** Opening tag of one grid cell — plain, `featured`, and lean variants alike. */
    private val CELL_START = Regex("""<p\b[^>]*class=["']wallpapers__item[^"']*["'][^>]*>""")

    /** Any anchor; attributes are read order-agnostically afterwards. */
    private val ANY_ANCHOR = Regex("""<a\s[^>]*>""")

    /** Any `<img …>` tag. */
    private val ANY_IMG = Regex("""<img\b[^>]*>""")

    /** The cell's `itemprop="keywords"` meta — title plus the site's own tags. */
    private val KEYWORDS_META = Regex("""<meta\b[^>]*itemprop=["']keywords["'][^>]*>""")

    /** The cell's `itemprop="contentUrl"` link — the 800px preview. */
    private val CONTENT_URL = Regex("""<link\b[^>]*itemprop=["']contentUrl["'][^>]*>""")

    /** The pagination bar — the site's own signal for what comes next. */
    private val PAGES_BAR =
        Regex(
            """<p\b[^>]*class=["']pages["'][^>]*>(.*?)</p>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** A page number inside a query string, after entity unescaping. */
    private val PAGE_IN_QUERY = Regex("""[?&]page=(\d+)""")

    /** The wallpaper page path shape: `/{category}/{slug}-{id}.html`. */
    private val DETAIL_SHAPE = Regex("""^/[^/?#]+/[^/?#]+-\d+\.html$""")

    /** The `Download Original (5120x2880)` link, icon markup and all. */
    private val DOWNLOAD_ORIGINAL =
        Regex(
            """<a\s[^>]*href=["']([^"']+)["'][^>]*>\s*<i\b[^>]*>\s*</i>\s*Download\s+Original\s*\(\s*(\d+)\s*[x×]\s*(\d+)\s*\)""",
        )

    /** The page's own preview image — `img itemprop="contentUrl"`. */
    private val MAIN_CONTENT_IMG = Regex("""<img\b[^>]*itemprop=["']contentUrl["'][^>]*>""")

    /** The preview directory family: `thumbs`, `thumbs_2t`, `thumbs_3t`. */
    private val THUMB_DIR = Regex("""^(.*?/images/walls/)thumbs[^/]*/(\d+\.[a-zA-Z0-9]+)$""")

    private const val BASE_URL = "https://4kwallpapers.com"

    /**
     * The grid of any listing page — the homepage's trending feed, the
     * popular ranking, a category, search results: the site serves one
     * shared cell shape for all of them. Cells are parsed slice by slice
     * (each cell's chunk runs from its own opening `p` to the next one),
     * so a malformed cell cannot swallow its neighbors. A cell without a
     * recognizable wallpaper-page link or preview drops.
     */
    fun parseGrid(html: String): List<GridItem> {
        val starts = CELL_START.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        return starts
            .indices
            .mapNotNull { i ->
                val from = starts[i]
                val to = if (i + 1 < starts.size) starts[i + 1] else html.length
                parseCell(html.substring(from, to))
            }.toList()
            // The homepage stacks two grids — trending plus a featured
            // carousel — and both parse; a cell surfacing in both must not
            // surface twice in the feed.
            .distinctBy { it.id }
    }

    /**
     * One cell, in two flavors the site actually serves: the full listing
     * cell (keywords meta + contentUrl link + thumbnail img) and the lean
     * related-cell on wallpaper pages (anchor + img only, but the img's
     * `alt` repeats the very same title-and-tags string). The preview falls
     * back from the cell's own contentUrl link to its thumbnail img; the
     * title and tags fall back from the keywords meta to that alt.
     */
    private fun parseCell(cell: String): GridItem? {
        val id =
            ANY_ANCHOR
                .findAll(cell)
                .mapNotNull { anchor -> pagePath(attributes(anchor.value)["href"].orEmpty()) }
                .firstOrNull()
                ?: return null
        val contentUrl = CONTENT_URL.find(cell)?.let { attributes(it.value)["href"] }
        val img =
            ANY_IMG
                .findAll(cell)
                .firstOrNull { tag -> attributes(tag.value)["src"].orEmpty().contains("/images/walls/thumbs") }
        val thumbPath = contentUrl ?: img?.let { attributes(it.value)["src"] } ?: return null
        val originalUrl = toOriginalUrl(absolutize(thumbPath)) ?: return null
        val keywords = keywordsOf(cell) ?: img?.let { attributes(it.value)["alt"] }
        val segments =
            unescapeEntities(keywords.orEmpty())
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return GridItem(
            id = id,
            thumbUrl = absolutize(thumbPath),
            originalUrl = originalUrl,
            title = segments.firstOrNull().orEmpty(),
            tags = segments.drop(1),
        )
    }

    /**
     * The next page number, read from the pagination bar's own `Next ›`
     * link (the anchor carrying `class="ctrl-right"`) — the site's
     * authoritative "more exists" signal. The link is absent on the last
     * page (verified live), and past-the-end page requests repeat the last
     * page's content, so blindly incrementing would loop the final batch
     * forever; this is the stopper. Search pages carry no bar at all, which
     * reads as "nothing more" — they are single-page by design.
     */
    fun parseNextPage(html: String): Int? {
        val bar = PAGES_BAR.find(html)?.groupValues?.get(1) ?: return null
        val href =
            ANY_ANCHOR
                .findAll(bar)
                .firstOrNull { anchor -> attributes(anchor.value)["class"].orEmpty().contains("ctrl-right") }
                ?.let { attributes(it.value)["href"] }
                ?: return null
        return PAGE_IN_QUERY
            .find(unescapeEntities(href))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    /**
     * The definitive record of a wallpaper page: the `Download Original`
     * link's URL and TRUE dimensions, plus the page-level keywords meta's
     * title and tags. The original has one fallback — the page's own
     * contentUrl image mapped back to the `orig` directory — so a download
     * menu redesign degrades to an unlabeled original before it breaks.
     */
    fun parseDetail(html: String): DetailRecord? {
        val labeled = DOWNLOAD_ORIGINAL.find(html)
        val width = labeled?.groupValues?.get(2)?.toIntOrNull()
        val height = labeled?.groupValues?.get(3)?.toIntOrNull()
        val mainImg = MAIN_CONTENT_IMG.find(html)?.let { attributes(it.value)["src"] }
        val originalUrl =
            labeled?.groupValues?.get(1)?.let { absolutize(it) }
                ?: mainImg?.let { toOriginalUrl(absolutize(it)) }
                ?: return null
        val segments = keywordsSegments(html)
        return DetailRecord(
            originalUrl = originalUrl,
            width = width,
            height = height,
            thumbUrl = mainImg?.let { absolutize(it) },
            title = segments.firstOrNull(),
            tags = segments.drop(1),
        )
    }

    /**
     * `…/images/walls/thumbs/{id}.jpg` (any thumb directory) to
     * `…/images/walls/orig/{id}.jpg` — the original shares the thumbnail's
     * filename, only the directory differs (verified live: `orig/` serves
     * the multi-megabyte source, e.g. 5120x2880 at 5.8 MB). Anything not a
     * thumbs CDN path is unrecognized and answered with null.
     */
    fun toOriginalUrl(thumbUrl: String): String? = THUMB_DIR.find(thumbUrl)?.let { "${it.groupValues[1]}orig/${it.groupValues[2]}" }

    /** Site-relative paths become absolute; anything else passes through. */
    fun absolutize(url: String): String = url.takeIf { it.startsWith("/") }?.let { BASE_URL + it } ?: url

    /**
     * An anchor href to the wallpaper's page path (`abstract/…-27263.html`)
     * — site-relative or absolute, query and fragment stripped. Anything
     * else (tag pages, category roots, external links) is null.
     */
    private fun pagePath(href: String): String? {
        if (href.isBlank()) return null
        val path =
            when {
                href.startsWith("$BASE_URL/") -> href.removePrefix("$BASE_URL/")
                href.startsWith("https://4kwallpapers.com/") -> href.removePrefix("https://4kwallpapers.com/")
                href.startsWith("/") -> href
                else -> return null
            }.substringBefore('?').substringBefore('#')
        if (!DETAIL_SHAPE.matches(path)) return null
        return path.trimStart('/')
    }

    /** The title-and-tags segments of a chunk's keywords meta, if present. */
    private fun keywordsSegments(html: String): List<String> {
        val keywords = keywordsOf(html) ?: return emptyList()
        return unescapeEntities(keywords)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** The `content` attribute of the first keywords meta in the chunk. */
    private fun keywordsOf(chunk: String): String? = KEYWORDS_META.find(chunk)?.let { attributes(it.value)["content"] }

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

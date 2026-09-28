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
 * identical page — and unknown collections answer a clean 404 whose title
 * says "Page not found".
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

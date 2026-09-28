package com.cloudimage.wallpapersafari

/**
 * Hand-rolled HTML mining for wallpapersafari.com — the scraping half of
 * this provider, kept free of any parsing library on purpose.
 *
 * Extension packages carry ONLY their own classes: the build dexes the module
 * jar and nothing else, so a dependency like Jsoup would be missing at load
 * time (the host supplies the contract and kotlinx.serialization, nothing
 * more). Everything below is therefore stdlib string and regex work over the
 * site's server-rendered markup — exactly the technique the CloudStream
 * extension ecosystem uses against page-embedded data.
 *
 * The site serves TWO grid shapes, both captured live and kept narrow here:
 * the home page's "Popular wallpapers" wall (self-closing `gallery-item`
 * anchors with the like widget INSIDE) and the topic galleries'
 * `post-image` blocks (the like widget in a sibling `imginfo` div, which is
 * why those cells slice by their wrapping div, not by the anchor). Search
 * and category pages share one card shape (`rel_a`), distinguished from the
 * zero-result page's TRENDING cards only by the page's own hit/miss
 * headings — so the markers are read, not guessed. Every parser keys on
 * stable, semantic anchors rather than document order, so cosmetic
 * redesigns degrade parsing to "nothing found" instead of producing
 * garbage. The site uses BOTH quote styles (single in headings, double in
 * grids), so every attribute reader accepts either. All functions are pure
 * and total: bad input yields empty lists and nulls, never exceptions —
 * callers decide what a miss means.
 */
internal object WallpaperSafariParser {
    /** One wallpaper cell, from the home wall or a topic gallery. */
    data class GridItem(
        /** The wallpaper's short id, e.g. `TvuM20` — the `/w/{id}` page slug and the provider's stable id. */
        val id: String,
        /** Absolute medium-thumbnail URL (`mcdn…/medium/{a}/{b}/{id}.{ext}`) — the site's uniform card image. */
        val thumbUrl: String,
        /** Absolute original URL (`cdn.wallpapersafari.com/{a}/{b}/{id}.{ext}`) — the multi-resolution file itself. */
        val originalUrl: String,
        /** The file's TRUE width, as the site itself publishes it (null when a cell's markup dropped it). */
        val width: Int?,
        /** The file's TRUE height. */
        val height: Int?,
        /** Display title from the cell's own alt text; the home wall's generic `1920x1080 wallpaper` alts yield null. */
        val title: String?,
        /** The uploader's username, when the cell carries it. */
        val author: String?,
        /** The topic gallery this item belongs to (`data-pageslug`); blank on the home wall. */
        val topicSlug: String?,
    )

    /** One topic-gallery card, from search results or a category directory. */
    data class TopicCard(
        /** The gallery's path slug, e.g. `indian-actress-wallpapers` — the `/{slug}/` page. */
        val slug: String,
        /** Display title, e.g. `Indian Actress Wallpapers`. */
        val title: String,
        /** The card's own `89 images` count, when present. */
        val imageCount: Int?,
    )

    /** The definitive record of a `/w/{id}` page. */
    data class DetailRecord(
        val originalUrl: String,
        /** The file's TRUE dimensions, straight from the main image's own attributes. */
        val width: Int?,
        val height: Int?,
        val title: String?,
        val author: String?,
        /** The gallery the wallpaper belongs to (`data-pageslug` on the main anchor). */
        val topicSlug: String?,
    )

    /** Attribute splitter: `name="value"` and `name='value'` pairs, any order. */
    private val ATTR =
        Regex(
            """([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        )

    /** Any anchor's opening tag; the grid cells and cards are all anchors. */
    private val ANCHOR = Regex("""<a\s[^>]*>""")

    /** Any `<div …>` opening tag; topic cells wrap their content in one. */
    private val DIV = Regex("""<div\b[^>]*>""")

    /** Any `<img …>` tag. */
    private val ANY_IMG = Regex("""<img\b[^>]*>""")

    /** Any `<span …>` opening tag. */
    private val SPAN = Regex("""<span\b[^>]*>""")

    /** The home wall's cell anchor: `href="/w/{id}"` plus a `gallery-item` class. */
    private val WALLPAPER_HREF = Regex("""^/w/([A-Za-z0-9]+)$""")

    /** A topic slug path: `/{slug}/`, lowercase letters/digits/hyphens, one segment. */
    private val TOPIC_SLUG = Regex("""^/([a-z0-9][a-z0-9-]*)/$""")

    /** The search page's own hit heading — `… wallpaper galleries we found`. */
    private val RESULTS_FOUND = Regex("""galleries\s+we\s+found""")

    /** The search page's own miss heading — `couldn't find anything`, any apostrophe spelling. */
    private val NO_RESULTS = Regex("""couldn.{0,4}t\s+find\s+anything""")

    /** The card's title line and its image count. */
    private val RTITLE = Regex("""class=["']rtitle["'][^>]*>\s*([^<]*?)\s*<""")
    private val RAMOUNT = Regex("""class=["']ramount["'][^>]*>\s*(\d[\d,]*)\s*[^<]*<""")

    /** The detail page's H1. */
    private val H1 = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)

    /** The detail page's main anchor — `imgshadow_single` — carrying author and gallery. */
    private val SINGLE_ANCHOR_CLASS = "imgshadow_single"

    /** The generic alt the home wall stamps on every cell — not a title. */
    private val GENERIC_ALT = Regex("""^\d{3,5}\s*[x×]\s*\d{3,5}(\s+wallpaper)?$""", RegexOption.IGNORE_CASE)

    private const val MEDIUM_PREFIX = "https://mcdn.wallpapersafari.com/medium/"

    private const val ORIGINAL_PREFIX = "https://cdn.wallpapersafari.com/"

    /**
     * The home page's "Popular wallpapers" wall. Each cell is one
     * self-contained `gallery-item` anchor — the image and the like widget
     * sit INSIDE it — so a cell runs from its anchor's opening tag to the
     * first `</a>` that follows (anchors cannot nest). Cells whose anchor
     * or like widget lack the fields the contract needs drop; their
     * neighbors survive them.
     */
    fun parseHomeGrid(html: String): List<GridItem> =
        ANCHOR
            .findAll(html)
            .mapNotNull { match ->
                val attrs = attributes(match.value)
                val href = attrs["href"] ?: return@mapNotNull null
                val id = WALLPAPER_HREF.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val classes = attrs["class"] ?: return@mapNotNull null
                if ("gallery-item" !in classes) return@mapNotNull null
                val end = html.indexOf("</a>", match.range.last)
                if (end < 0) return@mapNotNull null
                homeCell(id, attrs, html.substring(match.range.first, end))
            }.toList()

    /**
     * One home cell's fields: the medium thumbnail from the anchor's
     * `data-img`, the original from the like widget's `data-path` (the
     * site's own CDN path for the full file), the TRUE dimensions from the
     * like widget's `data-width`/`data-height`, and the uploader from
     * `data-username`. The home wall's alt texts are generic resolution
     * labels, so a generic alt yields no title rather than noise.
     */
    private fun homeCell(
        id: String,
        anchorAttrs: Map<String, String>,
        cell: String,
    ): GridItem? {
        val thumbUrl = anchorAttrs["data-img"]?.takeIf { it.startsWith(MEDIUM_PREFIX) } ?: return null
        val like = likeWidget(cell)
        val path = like["data-path"] ?: return null
        val originalUrl = ORIGINAL_PREFIX + path
        val width = like["data-width"]?.toIntOrNull()
        val height = like["data-height"]?.toIntOrNull()
        val title =
            firstImg(cell)?.let { img ->
                cleanTitle(attributes(img)["alt"])
            }
        return GridItem(
            id = id,
            thumbUrl = thumbUrl,
            originalUrl = originalUrl,
            width = width,
            height = height,
            title = title,
            author = anchorAttrs["data-username"]?.takeIf { it.isNotBlank() },
            topicSlug = anchorAttrs["data-pageslug"]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * A topic gallery's wallpaper wall. Each cell is one `post-image` div
     * wrapping the image anchor, with the like widget in a SIBLING `imginfo`
     * div — so cells slice from one `post-image` opening tag to the next,
     * which keeps every cell's own widgets inside its own slice. The
     * original URL comes straight from the cell's main `<img src>` (topic
     * pages embed the full CDN file there), with the like widget's
     * `data-path` as the fallback.
     */
    fun parseTopicGrid(html: String): List<GridItem> {
        val starts =
            DIV
                .findAll(html)
                .filter { match -> "post-image" in (attributes(match.value)["class"] ?: "") }
                .map { it.range.first }
                .toList()
        if (starts.isEmpty()) return emptyList()
        return starts
            .indices
            .mapNotNull { i ->
                val from = starts[i]
                val to = if (i + 1 < starts.size) starts[i + 1] else html.length
                topicCell(html.substring(from, to))
            }.toList()
    }

    /** One topic cell's fields, read from its own anchor, image, and like widget. */
    private fun topicCell(cell: String): GridItem? {
        val anchor =
            ANCHOR.findAll(cell).firstOrNull { match ->
                val attrs = attributes(match.value)
                attrs["href"] == "/w/${attrs["data-id"].orEmpty()}" && "imgshadow" in (attrs["class"] ?: "")
            } ?: return null
        val anchorAttrs = attributes(anchor.value)
        val id = anchorAttrs["data-id"]?.takeIf { it.isNotBlank() } ?: return null
        val thumbUrl = anchorAttrs["data-img"]?.takeIf { it.startsWith(MEDIUM_PREFIX) } ?: return null
        val mainImg = firstImg(cell)
        val imgAttrs = mainImg?.let(::attributes).orEmpty()
        val originalUrl =
            imgAttrs["src"]?.takeIf { it.startsWith(ORIGINAL_PREFIX) }
                ?: likeWidget(cell)["data-path"]?.let { ORIGINAL_PREFIX + it }
                ?: return null
        val like = likeWidget(cell)
        val width = (imgAttrs["width"] ?: like["data-width"])?.toIntOrNull()
        val height = (imgAttrs["height"] ?: like["data-height"])?.toIntOrNull()
        return GridItem(
            id = id,
            thumbUrl = thumbUrl,
            originalUrl = originalUrl,
            width = width,
            height = height,
            title = cleanTitle(imgAttrs["alt"]),
            author = anchorAttrs["data-username"]?.takeIf { it.isNotBlank() },
            topicSlug = anchorAttrs["data-pageslug"]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The topic-gallery cards of a search results page or a category
     * directory — one shared `rel_a` shape. The href is the gallery's own
     * path, the card's `title` attribute and `rtitle` line carry its name,
     * and `ramount` its image count. Cards whose href is not a bare topic
     * path drop.
     */
    fun parseTopicCards(html: String): List<TopicCard> =
        ANCHOR
            .findAll(html)
            .mapNotNull { match ->
                val attrs = attributes(match.value)
                if ("rel_a" !in (attrs["class"] ?: "")) return@mapNotNull null
                val slug = TOPIC_SLUG.find(attrs["href"] ?: return@mapNotNull null)?.groupValues?.get(1) ?: return@mapNotNull null
                val end = html.indexOf("</a>", match.range.last)
                if (end < 0) return@mapNotNull null
                val card = html.substring(match.range.first, end)
                val title =
                    cleanTitle(
                        attrs["title"].orEmpty().ifBlank {
                            RTITLE
                                .find(card)
                                ?.groupValues
                                ?.get(1)
                                .orEmpty()
                        },
                    )
                        ?: slug
                TopicCard(
                    slug = slug,
                    title = title,
                    imageCount =
                        RAMOUNT
                            .find(card)
                            ?.groupValues
                            ?.get(1)
                            ?.replace(",", "")
                            ?.toIntOrNull(),
                )
            }.toList()

    /**
     * The search page's own verdict that the query matched: the hit page
     * heads its results with `… wallpaper galleries we found`. Read from
     * the page itself — never inferred from card counts, because the
     * zero-result page fills itself with trending suggestions.
     */
    fun hasResultsMarker(html: String): Boolean = RESULTS_FOUND.find(html) != null

    /**
     * The search page's own verdict that nothing matched: `Oooops! …
     * couldn't find anything`, above a wall of trending cards this parser
     * must never mistake for results.
     */
    fun hasNoResultsMarker(html: String): Boolean = NO_RESULTS.find(html) != null

    /**
     * The definitive record of a `/w/{id}` page: the H1 title, the main
     * image (the full-resolution file with its TRUE dimensions in the
     * site's own attributes), the uploader, and the gallery the wallpaper
     * belongs to. The original URL falls back to `og:image` — the same CDN
     * file — when the main anchor's markup moves.
     */
    fun parseDetail(html: String): DetailRecord? {
        val single =
            ANCHOR.findAll(html).firstOrNull { match -> SINGLE_ANCHOR_CLASS in (attributes(match.value)["class"] ?: "") }
        val singleAttrs: Map<String, String> = single?.value?.let(::attributes) ?: emptyMap()
        val mainImg =
            if (single != null) {
                val end = html.indexOf("</a>", single.range.last)
                if (end > single.range.first) firstImg(html.substring(single.range.first, end)) else null
            } else {
                null
            }
        val imgAttrs = mainImg?.let(::attributes).orEmpty()
        val originalUrl =
            imgAttrs["src"]?.takeIf { it.startsWith(ORIGINAL_PREFIX) }
                ?: ogImage(html)?.takeIf { it.startsWith(ORIGINAL_PREFIX) }
                ?: return null
        val title =
            H1
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.let { cleanTitle(stripTags(it)) }
                ?: cleanTitle(imgAttrs["alt"])
        return DetailRecord(
            originalUrl = originalUrl,
            width = imgAttrs["width"]?.toIntOrNull(),
            height = imgAttrs["height"]?.toIntOrNull(),
            title = title,
            author =
                singleAttrs["data-username"]?.takeIf { it.isNotBlank() }
                    ?: singleAttrs["data-firstname"]?.takeIf { it.isNotBlank() },
            topicSlug = singleAttrs["data-pageslug"]?.takeIf { it.isNotBlank() },
        )
    }

    // -------------------------------------------------------------- helpers

    /**
     * `https://mcdn.wallpapersafari.com/medium/{a}/{b}/{id}.{ext}` to the
     * original `https://cdn.wallpapersafari.com/{a}/{b}/{id}.{ext}` — the
     * same file path, one CDN and one size directory up (verified live:
     * the medium card is a small JPEG crop, the cdn root the full file,
     * extension included). Anything else is unrecognized and answered
     * with null.
     */
    fun toOriginalUrl(mediumUrl: String): String? =
        mediumUrl
            .takeIf { it.startsWith(MEDIUM_PREFIX) }
            ?.removePrefix(MEDIUM_PREFIX)
            ?.let { ORIGINAL_PREFIX + it }

    /** The preview URL of an original: the same file back under `medium`. */
    fun toThumbUrl(originalUrl: String): String? =
        originalUrl
            .takeIf { it.startsWith(ORIGINAL_PREFIX) }
            ?.removePrefix(ORIGINAL_PREFIX)
            ?.let { MEDIUM_PREFIX + it }

    /** The like widget's opening tag — the one span carrying a `data-path`. */
    private fun likeWidget(cell: String): Map<String, String> =
        SPAN.findAll(cell).firstOrNull { match -> attributes(match.value).containsKey("data-path") }?.let { attributes(it.value) }
            ?: emptyMap()

    /** The first `<img …>` of a chunk, when there is one. */
    private fun firstImg(chunk: String): String? = ANY_IMG.find(chunk)?.value

    /** The `og:image` meta's content, attribute order aside. */
    private fun ogImage(html: String): String? =
        Regex("""<meta\b[^>]*og:image[^>]*>""")
            .find(html)
            ?.let { match -> attributes(match.value)["content"] }

    /** Tags stripped, whitespace squeezed — for heading text. */
    private fun stripTags(markup: String): String = markup.replace(Regex("""<[^>]+>"""), " ")

    /**
     * A cell alt or card title cleaned: entities unescaped, whitespace
     * squeezed. A generic home-wall alt (`1920x1080 wallpaper`) or an
     * empty string yields null — no title is better than a wrong one.
     */
    private fun cleanTitle(raw: String?): String? {
        val text = unescapeEntities(raw.orEmpty()).replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty() || GENERIC_ALT.matches(text)) return null
        return text
    }

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

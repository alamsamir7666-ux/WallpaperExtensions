package com.cloudimage.mordeo

/**
 * Reads mordeo.org's server-rendered pages — the same shapes its own
 * browser UI is built from, captured live and pinned by the fixture tests.
 *
 * The site is a WordPress blog of portrait phone wallpapers (the theme's
 * own masonry), and every listing — the home stream (`/`, `/page/N/`),
 * every category (`/wallpapers/category/{slug}/page/N/`), every tag
 * (`/wallpapers/tag/{slug}/page/N/`) and search (`/?s=`, then
 * `/page/N/?s=`) — speaks the SAME card: an `<li class="post-{id} …
 * category-{cat} tag-{tag} …">` cell inside `<ul id="masonry_list">`,
 * holding the wallpaper's page link, its card image (the `src` crop plus
 * a `srcset` of larger copies) and its `<h2 class="article-title">` —
 * six cells per page, walked by the theme's wp-pagenavi bar whose
 * `nextpostslink` is the "more exists" signal and whose absence is the
 * stop. A page the site cannot answer — a search with no matches —
 * simply ships the list EMPTY; zero parsed cells is the honest answer.
 *
 * The card's `li` class list carries the wallpaper's own WordPress
 * taxonomy (its real tags), and the card image's filename carries its
 * original: every file the site uploads lands at
 * `/files/uploads/{year}/{month}/{name}.jpg`, and the card and detail
 * images are that name with a size suffix — `-{WxH}` for the theme's
 * crops (`.webp` on newer posts, `.jpg` on older ones), `-Preview` for
 * the earliest era's hand-made previews. Stripping the suffix and
 * speaking `.jpg` answers the original deterministically, verified live
 * across all four eras of the site (2016 preview, 2017 and 2019 sized,
 * 2026 webp — seven for seven, byte-identical to the download link the
 * detail pages carry).
 *
 * A wallpaper page (`/wallpapers/{slug}/`) discloses the rest: the `h1`
 * title, the display image (the theme's single-image copy), the download
 * button (`class="simplebtn"` — a direct `/files/uploads/` link on the
 * newer and oldest eras, a `/download/{postId}/` endpoint on the middle
 * one that serves the very same original bytes), the resolution prose
 * ("With 2160x3840 pixels …") carrying the TRUE dimensions, the site's
 * own tag row, and a canonical link naming the page's own slug.
 */
internal object MordeoParser {
    /**
     * One masonry cell of any listing page: the wallpaper's page slug (its
     * stable id — the URL is `/wallpapers/{slug}/`), the site's crispest
     * card-sized image, the deterministic original, the cell's title and
     * the site's own tags.
     */
    data class GridItem(
        val slug: String,
        val thumbUrl: String,
        val fullUrl: String,
        val title: String?,
        val tags: List<String>,
    )

    /**
     * The definitive record of a wallpaper page: the cleaned `h1` title,
     * the display image, the original's URL (the site's own direct link
     * when it ships one, else the derived static original — verified
     * byte-identical), the TRUE dimensions from the resolution prose, the
     * site's own tags and the page's canonical slug.
     */
    data class DetailRecord(
        val title: String,
        val displayThumbUrl: String?,
        val originalUrl: String?,
        val width: Int?,
        val height: Int?,
        val tags: List<String>,
        val canonicalSlug: String?,
    )

    /** The masonry list every listing speaks; empty when there is nothing. */
    private val masonryList =
        Regex(
            """<ul id="masonry_list"[^>]*>(.*?)</ul>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /** One card cell — `li class="post-{id} …">` blocks carry the taxonomy. */
    private val cardBlock =
        Regex(
            """<li class="(post-[^"]*)"[^>]*>(.*?)</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /** The wallpaper's page link inside a cell. */
    private val postLink = Regex("""<a href="https?://(?:www\.)?mordeo\.org/wallpapers/([\w-]+)/">""")

    /** The cell's card image — the masonry attachment. */
    private val cardImage = Regex("""<img[^>]*attachment-thumbnail-masonry[^>]*>""")

    /** The cell's title in its own heading. */
    private val cardTitle = Regex("""<h2 class="article-title">\s*<a[^>]*>([^<]+?)\s*</a>""")

    /** The image's `alt` text — the title's fallback. */
    private val altAttr = Regex("""\salt="([^"]*)"""")

    /** The tag slugs the cell's `li` class list carries. */
    private val classTag = Regex("""\btag-([\w-]+)""")

    /** The bar's "more exists" link — present whenever the stream continues. */
    private val nextLink = Regex("""class="nextpostslink"""")

    /** One srcset entry — a URL and its width. */
    private val srcsetEntry = Regex("""(\S+)\s+(\d+)w""")

    /** The wallpaper page's `h1` — its title. */
    private val titleHeading =
        Regex(
            """<h1 class="article-title">(.*?)</h1>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /** The page's display image — the theme's single-image attachment. */
    private val displayImage = Regex("""<img[^>]*attachment-thumbnail-single-image[^>]*>""")

    /** The original download button — direct on two eras, an endpoint on one. */
    private val downloadLink = Regex("""<a href="([^"]+)"[^>]*class="simplebtn"""")

    /** The resolution prose — the TRUE dimensions of the uploaded original. */
    private val resolutionProse = Regex("""With (\d+)x(\d+) pixels""")

    /** One tag of the site's own tag row. */
    private val tagLink =
        Regex(
            """<a href="[^"]*/wallpapers/tag/[\w-]+/"[^>]*rel="tag"[^>]*>([^<]+)</a>""",
        )

    /** The page's canonical link, naming the slug the site itself answers. */
    private val canonicalLink =
        Regex("""<link rel="canonical" href="https?://(?:www\.)?mordeo\.org/wallpapers/([\w-]+)/" ?/?>""")

    /**
     * The size/preview/scaled suffixes every derived image filename ends
     * with, before its extension.
     */
    private val imageSuffix = Regex("""(?:-(?:scaled|Preview)|-\d+x\d+)+$""")

    /** HTML entities WordPress titles and tags frequently carry. */
    private val entities =
        mapOf(
            "&amp;" to "&",
            "&lt;" to "<",
            "&gt;" to ">",
            "&quot;" to "\"",
            "&apos;" to "'",
            "&nbsp;" to " ",
            "&ndash;" to "–",
            "&mdash;" to "—",
            "&hellip;" to "…",
            "&middot;" to "·",
            "&rsquo;" to "’",
            "&lsquo;" to "‘",
            "&ldquo;" to "“",
            "&rdquo;" to "”",
        )

    /** Numeric character references, decimal and hexadecimal. */
    private val numericEntity = Regex("""&#(\d+);|&#x([\da-fA-F]+);""")

    /**
     * The grid of any listing page, in the site's own order. Titles come
     * from the cell's own heading, the image's `alt` as the fallback.
     * Thumbs are the srcset entry closest to the card's own 2x — the
     * retina-grade copy the site itself ships, never the multi-megabyte
     * originals — falling back to the cell's `src` when no larger copy
     * exists (the earliest era's previews). Fulls are the DETERMINISTIC
     * originals: the card filename minus its size suffix, spoken as
     * `.jpg` (verified live across all four eras). Cells without a
     * usable image or link drop; slugs dedupe.
     */
    fun parseGrid(html: String): List<GridItem> =
        masonryList
            .findAll(html)
            .flatMap { match -> match.groupValues[1].let(::parseCards) }
            .distinctBy { it.slug }
            .toList()

    /** The cells of one masonry list segment. */
    private fun parseCards(segment: String): List<GridItem> =
        cardBlock
            .findAll(segment)
            .mapNotNull { match ->
                val classes = match.groupValues[1]
                val body = match.groupValues[2]
                val slug = postLink.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
                if (slug.isBlank()) return@mapNotNull null
                val image = cardImage.find(body)?.value ?: return@mapNotNull null
                val src = attrOf(image, "src") ?: return@mapNotNull null
                val title =
                    textOf(cardTitle.find(body))
                        ?: textOf(altAttr.find(image))
                GridItem(
                    slug = slug,
                    thumbUrl = crispThumb(image, src),
                    fullUrl = originalFromImageUrl(src) ?: src,
                    title = title?.let(::decodeEntities),
                    tags = classTags(classes),
                )
            }.toList()

    /**
     * The site's own tags, read from the cell's `li` class list — the
     * WordPress taxonomy the theme prints on every card (`tag-{slug}`),
     * prettified from slugs to words.
     */
    private fun classTags(classes: String): List<String> =
        classTag
            .findAll(classes)
            .map { match -> match.groupValues[1] }
            .filterNot { it == "post" }
            .map(::prettifySlug)
            .distinct()
            .take(MAX_CARD_TAGS)
            .toList()

    /** A tag slug back to words: `dwayne-johnson` becomes `Dwayne Johnson`. */
    private fun prettifySlug(slug: String): String =
        slug
            .split('-')
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    /**
     * The crispest card-sized image: the smallest srcset entry at least as
     * wide as the card's own 2x, capped so the multi-megabyte originals
     * and their `-scaled` copies are never picked as thumbs; the card's
     * `src` when no entry qualifies (the earliest era ships none larger).
     */
    private fun crispThumb(
        image: String,
        src: String,
    ): String {
        val srcWidth = attrOf(image, "width")?.toIntOrNull() ?: return src
        val target = srcWidth * 2
        val entries =
            srcsetEntry
                .findAll(attrOf(image, "srcset").orEmpty())
                .mapNotNull { match ->
                    val width = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    match.groupValues[1] to width
                }.toList()
        return entries
            .filter { (_, width) -> width >= target && width <= THUMB_WIDTH_CAP }
            .minByOrNull { (_, width) -> width }
            ?.first
            ?: src
    }

    /**
     * Whether the stream continues after this page: the wp-pagenavi bar's
     * `nextpostslink`. The last page of a stream and every empty answer
     * carry none, so both read as the end.
     */
    fun hasMorePages(html: String): Boolean = nextLink.containsMatchIn(html)

    /**
     * The wallpaper page's definitive record, or null when the page does
     * not speak the shape this reads (a redesigned or redirected page).
     * The original prefers the site's own direct `/files/uploads/` link;
     * the middle era's `/download/{postId}/` endpoint serves the very
     * same bytes but as an octet-stream, so the DERIVED static original
     * — byte-identical, verified live — is preferred there.
     */
    fun parseDetail(html: String): DetailRecord? {
        val heading = titleHeading.find(html) ?: return null
        val title =
            heading
                .groupValues[1]
                .replace(Regex("""<[^>]+>"""), "")
                .trim()
                .let(::decodeEntities)
        if (title.isBlank()) return null
        val display = displayImage.find(html)?.value
        val displaySrc = display?.let { attrOf(it, "src") }
        val download = downloadLink.find(html)?.groupValues?.get(1)
        val dimensions = resolutionProse.find(html)
        return DetailRecord(
            title = title,
            displayThumbUrl = displaySrc,
            originalUrl = originalOf(download, displaySrc),
            width = dimensions?.groupValues?.get(1)?.toIntOrNull(),
            height = dimensions?.groupValues?.get(2)?.toIntOrNull(),
            tags = parseTags(html),
            canonicalSlug = canonicalLink.find(html)?.groupValues?.get(1),
        )
    }

    /**
     * The original's URL: the site's own direct file link when it ships
     * one, else the display image's stem spoken as `.jpg` (verified
     * byte-identical to the download endpoint the middle era ships).
     */
    private fun originalOf(
        download: String?,
        displaySrc: String?,
    ): String? =
        when {
            download != null && download.contains("/files/uploads/") -> download
            displaySrc != null -> originalFromImageUrl(displaySrc)
            download != null && download.startsWith("/") -> "https://www.mordeo.org$download"
            else -> null
        }

    /**
     * The deterministic original of any `/files/uploads/` image URL: the
     * filename minus its size (`-{WxH}`), `-Preview` or `-scaled`
     * suffixes, the extension spoken as `.jpg` — the very file the
     * wallpaper page's own download button carries, verified live across
     * all four eras of the site.
     */
    fun originalFromImageUrl(url: String): String? {
        if (!url.contains("/files/uploads/")) return null
        val stem = url.substringBeforeLast('.')
        if (!imageSuffix.containsMatchIn(stem)) return null
        return "${imageSuffix.replace(stem, "")}.jpg"
    }

    /** The site's own tag row, decoded and deduped. */
    private fun parseTags(html: String): List<String> =
        tagLink
            .findAll(html)
            .map { match -> decodeEntities(match.groupValues[1].trim()) }
            .distinct()
            .take(MAX_TAGS)
            .toList()

    /** One attribute's value out of an img tag's own text. */
    private fun attrOf(
        tag: String,
        name: String,
    ): String? = textOf(Regex("""\s$name="([^"]*)"""").find(tag))

    /** The first group of a match, trimmed; a blank one answers null. */
    private fun textOf(match: MatchResult?): String? =
        match
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * WordPress's frequent entities decoded — named first with `&amp;`
     * last so no double-decode, then the numeric character references.
     */
    private fun decodeEntities(text: String): String {
        var decoded = text
        entities.entries
            .sortedByDescending { (name, _) -> name.length }
            .filter { (name, _) -> name != "&amp;" }
            .forEach { (name, value) -> decoded = decoded.replace(name, value) }
        decoded =
            numericEntity.replace(decoded) { match ->
                val code =
                    match.groupValues[1]
                        .ifEmpty { match.groupValues[2] }
                        .toIntOrNull()
                        ?.toChar()
                code?.toString() ?: match.value
            }
        return decoded.replace("&amp;", "&").trim()
    }

    /** The card tags' honest cap. */
    private const val MAX_CARD_TAGS = 6

    /** The detail tags' honest cap. */
    private const val MAX_TAGS = 8

    /** Thumbs never wider than this — the card's own 2x, never the originals. */
    private const val THUMB_WIDTH_CAP = 1024
}

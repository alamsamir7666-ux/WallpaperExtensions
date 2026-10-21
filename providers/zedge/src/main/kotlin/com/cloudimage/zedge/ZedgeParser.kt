package com.cloudimage.zedge

/**
 * Reads zedge.net's wallpaper pages — the shapes its own Next.js UI
 * renders, captured from the site's live routes and pinned by the
 * fixture tests.
 *
 * Zedge is a FLAT directory: single wallpapers behind stable UUID
 * routes, reached through listing grids — the popular landing
 * (`/wallpapers`), category pages (`/category/wallpapers/{slug}`) and
 * search (`/find/{query}`) all speak the SAME tile, and there is no
 * album or collection layer anywhere in the grammar (unlike the
 * album-paradigm sources, every listing resolves straight to
 * wallpapers). The detail route is `/wallpaper/{uuid}` — the form the
 * site's own sitemap system publishes by the thousand
 * (`/sitemap2/item/wallpaper/000`…, 36-char lowercase hex UUIDs), so
 * the UUID is the wallpaper's stable id and the link grammar is pinned
 * by the site's own SEO plumbing.
 *
 * Every listing tile is an anchor to the detail route wrapping (or
 * accompanied by) the tile's image. The parser is SHAPE-ANCHORED on
 * purpose: it keys on the UUID route and on `<img>` tags alone, never
 * on class names or framework chrome — a Next.js reskin re-paints the
 * classes but the detail routes and the images must keep working for
 * the site's own SEO and users, so they are the honest load-bearing
 * shapes. The same anchoring keeps the parser immune to whatever CDN
 * host the images speak: tile thumbs are taken verbatim from the
 * `<img>` the site itself serves, never derived.
 *
 * Search pages (`/find/{query}`) are MIXED listings — the site
 * separates wallpapers from ringtones, notification sounds, dynamic
 * wallpapers and artists under its own section headers ("Wallpapers",
 * "Dynamic 24H Wallpapers", "Ringtones", "Notification Sounds",
 * "Artists"). The wallpapers section is scoped by those exact headers
 * so a ringtone query never leaks audio items into a wallpaper feed,
 * and a wallpapers section the site ships empty parses to the honest
 * zero.
 *
 * A wallpaper page (`/wallpaper/{uuid}`) discloses the rest: the `h1`
 * title, the hero image the page itself displays, the `og:image`
 * share rendition as the fallback, and its own tag row. The page the
 * site cannot answer renders its own "couldn't find it" screen — the
 * `h1` shape goes missing and the parser reports an unrecognized page
 * rather than inventing one.
 *
 * The grid's "Load more" is a client-side cursor the URL cannot
 * express; listing pages therefore parse ONE page each, and the
 * provider answers `nextPage = null` — the honest single-page answer,
 * never duplicated items to fake depth.
 */
internal object ZedgeParser {
    /**
     * One tile of any listing: the wallpaper's UUID (its stable id —
     * the detail route is `/wallpaper/{uuid}`), the tile's own image
     * verbatim (whatever CDN host the site speaks), and the tile's
     * title — the image's `alt` first, the anchor's own text when it
     * is a real title and not a download badge.
     */
    data class GridItem(
        val id: String,
        val thumbUrl: String,
        val title: String?,
    )

    /**
     * The definitive record of a wallpaper page: the cleaned `h1`
     * title, the hero image the page displays, the site's own
     * full-size rendition (the hero when it ships one, the
     * `og:image` as the fallback), and the site's tag row.
     */
    data class DetailRecord(
        val title: String,
        val displayThumbUrl: String?,
        val fullUrl: String?,
        val tags: List<String>,
    )

    /**
     * The 36-char hex UUID every wallpaper route speaks — lowercase in
     * the sitemap system, matched case-tolerantly for a DOM that may
     * speak either.
     */
    private const val UUID =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    /** One tile's anchor: `/wallpaper/{uuid}` — relative or absolute. */
    private val tileLink =
        Regex(
            """<a\b[^>]*href="(?:https?://(?:www\.)?zedge\.net)?/wallpaper/($UUID)""",
        )

    /** Any `<img>` tag — tiles' images, heroes, everything. */
    private val imageTag = Regex("""<img\b[^>]*>""")

    /** The wallpaper page's `h1` — its title. */
    private val titleHeading =
        Regex(
            """<h1\b[^>]*>(.*?)</h1>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /** The page's `og:image` share rendition — the fallback original. */
    private val ogImage =
        Regex(
            """<meta\b[^>]*property="og:image"[^>]*content="([^"]+)"|<meta\b[^>]*content="([^"]+)"[^>]*property="og:image"""",
        )

    /** One tag of the site's own tag row — anchors to `/tag/` routes. */
    private val tagAnchor =
        Regex(
            """<a\b[^>]*href="[^"]*/tag/[^"]*"[^>]*>([^<]+)</a>""",
        )

    /** An anchor's visible text, tags and entities still inside. */
    private val anchorText =
        Regex(
            """<a\b[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /**
     * The search page's section headers, HEADING-shaped on purpose:
     * exact-text headings (`<h2>Wallpapers</h2>` and kin), which the
     * site-wide nav links — `<a ...>Wallpapers</a>` — can never
     * match. The wallpapers section starts at its heading and ends
     * at the first heading of any other content kind; the heading
     * shape keeps nav chrome out of the scoping entirely.
     */
    private val wallpapersHeading =
        Regex(
            """<h[1-6]\b[^>]*>\s*Wallpapers\s*</h[1-6]>""",
        )

    private val otherSectionHeading =
        @Suppress("ktlint:standard:max-line-length")
        Regex(
            """<h[1-6]\b[^>]*>\s*(?:Dynamic[^<]*|Charging Animations|Ringtones|Notification Sounds|Artists)\s*</h[1-6]>""",
        )

    /** HTML entities the site's titles and tags frequently carry. */
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
            "&#x27;" to "'",
            "&#39;" to "'",
        )

    /** Numeric character references, decimal and hexadecimal. */
    private val numericEntity = Regex("""&#(\d+);|&#x([\da-fA-F]+);""")

    /**
     * The wallpaper tiles of a wallpaper-only listing page (the
     * landing, a category page), in the page's own order. Every
     * `/wallpaper/{uuid}` anchor pairs with the nearest image
     * between it and the next anchor — inside the anchor or beside
     * it, whichever the page speaks — so the tile's shape (image
     * wrapped, image adjacent) stops mattering. Anchors without an
     * image of their own drop; UUIDs dedupe (a "Load more" echo or a
     * repeated shelf lists the same wallpaper once).
     */
    fun parseGrid(html: String): List<GridItem> = tilesOf(html)

    /**
     * The wallpaper tiles of a search page, scoped to the site's own
     * "Wallpapers" section when its headers speak the heading shape:
     * the section runs from the last "Wallpapers" heading before the
     * first other content heading to that first other heading —
     * HEADING-shaped on purpose, because the site's own nav links
     * also say "Wallpapers" and "Ringtones" and must never scope
     * anything. When the headers cannot be read the route family
     * does the scoping instead: ringtones and notification sounds
     * live under their own routes, so every `/wallpaper/{uuid}`
     * anchor on the page is a wallpaper and all of them serve.
     */
    fun parseSearchGrid(html: String): List<GridItem> {
        val end =
            otherSectionHeading
                .find(html)
                ?.range
                ?.last ?: return tilesOf(html)
        val start =
            wallpapersHeading
                .findAll(html)
                .lastOrNull { match -> match.range.last < end }
                ?: return tilesOf(html)
        return tilesOf(html.substring(start.range.last + 1, end))
    }

    /** The tiles of one slice: anchors paired with their images. */
    private fun tilesOf(html: String): List<GridItem> {
        val links = tileLink.findAll(html).toList()
        if (links.isEmpty()) return emptyList()
        val images =
            imageTag
                .findAll(html)
                .map { match ->
                    Triple(
                        match.range.first,
                        attrOf(match.value, "src") ?: attrOf(match.value, "data-src"),
                        attrOf(match.value, "alt"),
                    )
                }.filter { (_, src, _) -> src != null }
                .toList()
        return links
            .mapIndexedNotNull { index, link ->
                val id = link.groupValues[1]
                if (id.isBlank()) return@mapIndexedNotNull null
                val windowStart = link.range.first
                val windowEnd = links.getOrNull(index + 1)?.range?.first ?: (windowStart + TILE_WINDOW)
                val image = images.firstOrNull { (position, _, _) -> position in windowStart until windowEnd }
                val src = image?.second ?: return@mapIndexedNotNull null
                GridItem(
                    id = id,
                    thumbUrl = src,
                    title = titleOf(html.substring(windowStart, windowEnd.coerceAtMost(html.length)), image.third),
                )
            }.distinctBy { it.id }
            .toList()
    }

    /**
     * A tile's title: the image's `alt` when the page ships one, else
     * the anchor's own text — but only when that text is a real
     * title, not the download badge every tile carries ("10", "500",
     * "1.2K"). Badge-only tiles answer null; the host renders a null
     * title as its placeholder, and details always brings the real
     * one.
     */
    private fun titleOf(
        slice: String,
        alt: String?,
    ): String? {
        val cleanAlt = alt?.let(::decodeEntities)?.takeIf { it.isNotBlank() && !isBadge(it) }
        if (cleanAlt != null) return cleanAlt
        return anchorText
            .findAll(slice)
            .map { match ->
                match
                    .groupValues[1]
                    .replace(Regex("""<[^>]+>"""), "")
                    .trim()
                    .let(::decodeEntities)
            }.firstOrNull { it.isNotBlank() && !isBadge(it) }
    }

    /** A download-count chip — never a title. */
    private fun isBadge(text: String): Boolean =
        text.replace(Regex("""\s"""), "").let { compact ->
            compact.isNotEmpty() && compact.matches(Regex("""\d[\d.,]*[KkMmBb]?"""))
        }

    /**
     * The wallpaper page's definitive record, or null when the page
     * does not speak the shape this reads — the site's own
     * "couldn't find it" screen for a dead UUID carries no `h1`
     * wallpaper title and reads as unrecognized, never as an empty
     * wallpaper. The full-size rendition is the hero image the page
     * itself displays (a wallpaper site shows its own product), the
     * `og:image` share rendition as the fallback — both the site's
     * own URLs for the same image, taken verbatim, never derived.
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
        val afterHeading = (html.indexOf("</h1>", heading.range.first) + 5).coerceAtMost(html.length)
        val hero =
            imageTag
                .findAll(html, afterHeading)
                .map { it.value }
                .mapNotNull { tag -> attrOf(tag, "src") }
                .firstOrNull { src -> !isChromeImage(src) }
        val og =
            ogImage
                .findAll(html)
                .mapNotNull { match ->
                    match.groupValues[1].ifEmpty { match.groupValues[2] }
                }.firstOrNull()
                ?.takeIf { it.isNotBlank() }
        return DetailRecord(
            title = title,
            displayThumbUrl = hero ?: og,
            fullUrl = hero ?: og,
            tags = parseTags(html),
        )
    }

    /** The site's own tag row — `/tag/` anchors, decoded and deduped. */
    private fun parseTags(html: String): List<String> =
        tagAnchor
            .findAll(html)
            .map { match -> decodeEntities(match.groupValues[1].trim()) }
            .filter { it.isNotBlank() && !isBadge(it) }
            .distinct()
            .take(MAX_TAGS)
            .toList()

    /**
     * The chrome images a wallpaper page carries that are never the
     * product: logos, icons and avatar sprites. The hero is the first
     * image whose URL does not speak them.
     */
    private fun isChromeImage(src: String): Boolean =
        src.contains("logo", ignoreCase = true) ||
            src.contains("icon", ignoreCase = true) ||
            src.contains("avatar", ignoreCase = true) ||
            src.contains("/favicon", ignoreCase = true)

    /** One attribute's value out of a tag's own text. */
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
     * The entities the site's titles and tags frequently carry —
     * named first with `&amp;` last so no double-decode, then the
     * numeric character references.
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
                    match
                        .groupValues[1]
                        .ifEmpty { match.groupValues[2] }
                        .toIntOrNull()
                        ?.toChar()
                code?.toString() ?: match.value
            }
        return decoded.replace("&amp;", "&").trim()
    }

    /** How far past the last anchor a tile's image may sit. */
    private const val TILE_WINDOW = 2000

    /** The detail tags' honest cap. */
    private const val MAX_TAGS = 8
}

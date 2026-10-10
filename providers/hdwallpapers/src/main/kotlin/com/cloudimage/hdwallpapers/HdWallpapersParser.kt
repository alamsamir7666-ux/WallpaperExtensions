package com.cloudimage.hdwallpapers

/**
 * Reads hdwallpapers.in's server-rendered pages — the same shapes its own
 * browser UI is built from, captured live and pinned by the fixture tests.
 *
 * The site is listing-first and uniform: latest
 * (`/latest_wallpapers.html`), the two ranked feeds
 * (`/top_download_wallpapers.html`, `/top_view_wallpapers.html`), every
 * category (`/{slug}-desktop-wallpapers.html`), every tag
 * (`/tag/{name}.html`) and search (`/search.html?q=`) serve the SAME
 * wallpaper grid — twenty cells of
 * `<li class="wall"><div class="thumb"><a href="/{slug}-wallpapers.html">`
 * with a `/thumbs/{year}/{stem}-t1.jpg` image (the site's 1x grid copy)
 * and the wallpaper's title in the `alt` and `<em1>` — paginated by the
 * site's own bar, whose `Next` link is the "more exists" signal and whose
 * absence is the stop. Category pages carry a second section above the
 * grid, a sub-category directory that paginates under `pageSub/` — the
 * grid's own bar keeps `page/`, and only THAT bar is read.
 *
 * A page that runs past the end of its stream — and a search the site's
 * database cannot answer — answers with the same apology,
 * "Sorry, no wallpapers found", above the trending collection: the marker
 * is read so those cards are never served as results.
 *
 * A wallpaper page (`/{slug}-wallpapers.html`) discloses everything the
 * details surface wants: the `h1` title, the display image (the same
 * thumb stem at `-t2`, the site's 2x copy), the original download link
 * (`/download/{slug}-HD.jpg` — the deterministic original, derivable from
 * the slug alone), the generated resolution variants whose LARGEST entry
 * carries the file's TRUE dimensions (verified live: the `-HD` original
 * and the top variant share pixels, 3 for 3), and the site's own tag row
 * (`/tag/{name}.html` links, real tags first, SEO filler last).
 */
internal object HdWallpapersParser {
    /**
     * One grid cell of any listing page: the wallpaper's page slug (its
     * stable id — the URL is `/{slug}-wallpapers.html`), the site's 2x
     * thumb path, and the cell's own title.
     */
    data class GridItem(
        val slug: String,
        val thumbPath: String,
        val title: String?,
    )

    /**
     * The definitive record of a wallpaper page: the original's download
     * path, the TRUE dimensions from the page's largest resolution
     * variant, the cleaned `h1` title, the display thumb, and the site's
     * own tags — real content words, SEO filler dropped.
     */
    data class DetailRecord(
        val slug: String,
        val title: String,
        val displayThumbPath: String,
        val originalPath: String,
        val width: Int?,
        val height: Int?,
        val tags: List<String>,
    )

    /** One wallpaper cell: the anchor block, DOTALL-spanning to its close. */
    private val gridBlock =
        Regex(
            """<li class="wall"\s*>\s*<div class="thumb">\s*<a href="/([\w-]+)-wallpapers\.html">(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /** The cell's 1x grid image (year directory, stem, extension). */
    private val thumbSrc = Regex("""src="/thumbs/(\d+)/([\w-]+)-t1\.(\w+)"""")

    /** The cell's title, `alt` first, `<em1>` as the fallback. */
    private val altTitle = Regex("""alt="([^"]*)"""")
    private val emTitle = Regex("""<em1>\s*([^<]+?)\s*</em1>""")

    /** One pagination bar; category pages carry two (sub-category and grid). */
    private val paginationBar =
        Regex(
            """<div class="pagination">(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /** A link into a stream's `page/N` — the sub-category bar's `pageSub/N` never matches. */
    private val pageLink = Regex("""/page/\d+""")

    /** The bar's own "more exists" link — present whenever the stream continues. */
    private val nextLink = Regex(""">Next""")

    /** The apology every empty answer carries — search misses and past-the-end alike. */
    private val noResultsMarker = Regex("""Sorry, no wallpapers found""")

    /** The wallpaper page's `h1` — its title, with a stray close tag the template ships. */
    private val titleHeading =
        Regex(
            """<h1 class="title">(.*?)</h1>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /** The display image beside the original link — the thumb stem at its 2x size. */
    private val displayThumb =
        Regex(
            """class="wallpaper-thumb">\s*<img src="(/thumbs/[^"]+)"""",
        )

    /** The original download link — `/download/{slug}-HD.jpg`. */
    private val originalLink = Regex("""href="[^"]*/download/([\w-]+)-HD\.jpg"""")

    /** One generated resolution variant — `download/{slug}-{WxH}.jpg`, relative or rooted. */
    private val resolutionLink = Regex("""href="[^"]*download/[\w-]+-(\d+)x(\d+)\.jpg"""")

    /** One tag of the site's own tag row. */
    private val tagLink = Regex("""<a href="/tag/[\w-]+\.html"[^>]*>([^<]+)</a>""")

    /** Tags that describe the site's SEO, not the wallpaper — dropped. */
    private val seoTagMarker = Regex("""(?i)wallpaper|background|^free\b""")

    /** The `h1`'s trailing " Wallpaper" and the title's tag suffixes. */
    private val headingSuffix = Regex("""\s+Wallpaper$""")

    /**
     * The grid of any listing page, in the site's own order. Titles come
     * from the cell's `alt` (the site renders it on every cell) with
     * `<em1>` as the fallback; cells without a usable image drop; slugs
     * dedupe. Thumbs point at the `-t2` copy — the site's own 2x source
     * (`srcset`) and the very file its wallpaper pages display — so dense
     * Android screens render crisp at thumbnail price.
     */
    fun parseGrid(html: String): List<GridItem> =
        gridBlock
            .findAll(html)
            .mapNotNull { match ->
                val slug = match.groupValues[1]
                val body = match.groupValues[2]
                val thumb = thumbSrc.find(body) ?: return@mapNotNull null
                if (slug.isBlank()) return@mapNotNull null
                GridItem(
                    slug = slug,
                    thumbPath = "/thumbs/${thumb.groupValues[1]}/${thumb.groupValues[2]}-t2.${thumb.groupValues[3]}",
                    title = cellTitle(body),
                )
            }.distinctBy { it.slug }
            .toList()

    /** The cell's title: the `alt` text first, `<em1>` as the fallback. */
    private fun cellTitle(body: String): String? {
        val alt = textOf(altTitle.find(body))
        return alt ?: textOf(emTitle.find(body))
    }

    /** The first group of a match, trimmed; a blank one answers null. */
    private fun textOf(match: MatchResult?): String? =
        match
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * True when the page apologizes instead of answering — a search the
     * site's database cannot address, or a page request past the end of a
     * stream. The trending cards below the apology are the site's
     * suggestions and must never be served as results.
     */
    fun hasNoResultsMarker(html: String): Boolean = noResultsMarker.containsMatchIn(html)

    /**
     * Whether the stream continues after this page: the grid's own
     * pagination bar — the one whose links walk `page/N`, never the
     * sub-category bar's `pageSub/N` — carries its `Next` link. The last
     * page and every apologizing page carry none, so both read as the end.
     */
    fun hasMorePages(html: String): Boolean =
        paginationBar
            .findAll(html)
            .map { match -> match.groupValues[1] }
            .any { bar -> pageLink.containsMatchIn(bar) && nextLink.containsMatchIn(bar) }

    /**
     * The wallpaper page's definitive record, or null when the page does
     * not speak the shape this reads (a redesigned or redirected page).
     * The TRUE dimensions come from the resolution list's largest entry —
     * the original's own size, the site generating every smaller variant
     * from it (verified live: the `-HD` original and the top variant
     * share pixels). A page without variants answers null dimensions
     * rather than a guess.
     */
    fun parseDetail(html: String): DetailRecord? {
        val heading = titleHeading.find(html) ?: return null
        val title =
            heading
                .groupValues[1]
                .replace(Regex("""<[^>]+>"""), "")
                .replace(headingSuffix, "")
                .trim()
        val slug = originalLink.find(html)?.groupValues?.get(1) ?: return null
        if (title.isBlank()) return null
        val display = displayThumb.find(html)?.groupValues?.get(1)
        val dimensions =
            resolutionLink
                .findAll(html)
                .map { match ->
                    val width = match.groupValues[1].toIntOrNull() ?: 0
                    val height = match.groupValues[2].toIntOrNull() ?: 0
                    width to height
                }.filter { (width, height) -> width > 0 && height > 0 }
                .maxByOrNull { (width, height) -> width * height }
        return DetailRecord(
            slug = slug,
            title = title,
            displayThumbPath = display ?: "",
            originalPath = "/download/$slug-HD.jpg",
            width = dimensions?.first,
            height = dimensions?.second,
            tags = parseTags(html),
        )
    }

    /**
     * The site's own tag row: real content words first, the SEO filler
     * the row appends last ("hd wallpapers", "free download hd
     * wallpapers" …) dropped by pattern, the head of what remains capped.
     */
    private fun parseTags(html: String): List<String> =
        tagLink
            .findAll(html)
            .map { match -> match.groupValues[1].trim() }
            .filterNot { name -> seoTagMarker.containsMatchIn(name) }
            .distinct()
            .take(MAX_TAGS)
            .toList()

    /** The tags' honest cap — the site's real tags sit at the row's head. */
    private const val MAX_TAGS = 8
}

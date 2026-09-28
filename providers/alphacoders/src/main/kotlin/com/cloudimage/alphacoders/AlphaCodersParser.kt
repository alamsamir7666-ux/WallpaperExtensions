package com.cloudimage.alphacoders

/**
 * Regex miner for the Wallpaper Abyss listings at alphacoders.com, the
 * wallpaper section of Alpha Coders.
 *
 * The site speaks schema.org everywhere, generously. Every listing cell —
 * on the ranked feeds (`/popular-wallpapers`, `/newest-wallpapers`) and on
 * every topic page (`/anime-wallpapers`, `/naruto-wallpapers`, …) — is an
 * `itemprop="associatedMedia"` `ImageObject` whose metas disclose the
 * original file itself (`contentUrl`, `https://images{N}.alphacoders.com/
 * {shard}/{id}.{ext}` — a plain JPG/PNG the CDN serves directly, verified
 * byte-for-byte), the 350-pixel WebP grid thumbnail (`thumbnailUrl`), the
 * re-fetch address (`url`, `wall.alphacoders.com/big.php?i={id}`), the
 * site's own keyword row and a caption. The detail page tops that up with
 * the file's TRUE dimensions (the `main-content` img's width/height — the
 * listing's 350x219 attrs are its uniform card crop, true of no wallpaper),
 * an author credit, a File Info box (`3840x2400 1.38 MB JPG`), and the
 * site's own color row.
 *
 * All patterns are attribute-order agnostic within a tag (`[^<>]*` gaps,
 * never `\s*`, so a match can never leak across tag boundaries) and quote
 * style agnostic where the site itself mixes (`color-info` anchors use
 * single quotes). Entities (`&amp;`) are unescaped in every human-readable
 * field. Fields the markup does not carry arrive as null or empty — honest
 * emptiness over invented values.
 */
object AlphaCodersParser {
    /** One listing cell: everything the grid item needs, nothing it doesn't. */
    data class GridItem(
        /** The big.php image id — the re-fetch address for [details]. */
        val id: String,
        /** The cell's own name meta when the site fills it, else the first real keyword. */
        val title: String?,
        /** The published 350-pixel WebP thumbnail, verbatim. */
        val thumbUrl: String,
        /** The original file, disclosed by the cell itself. */
        val originalUrl: String,
        /** The site's keyword row minus its boilerplate tail. */
        val tags: List<String>,
    )

    /** The detail page's definitive record. Nulls where the page is silent. */
    data class DetailRecord(
        val originalUrl: String?,
        val previewUrl: String?,
        val width: Int?,
        val height: Int?,
        val title: String?,
        val author: String?,
        val fileSizeBytes: Long?,
        val tags: List<String>,
        val description: String?,
        /** The site's own color row, `#rrggbb` form, deduped. */
        val colors: List<String>,
    )

    /** The site's boilerplate keyword tail, present on every observed cell. */
    private val BOILERPLATE_TAGS =
        setOf(
            "desktop wallpaper",
            "background",
            "hd wallpaper",
            "8k ultra hd",
            "8k ultra hd wallpaper",
        )

    /** Every meta carrying [propName], in document order, both attribute orders. */
    private fun metaValues(
        html: String,
        propName: String,
    ): List<String> {
        val forward = Regex("""itemprop="$propName"[^<>]*(?:content|href)="([^"]*)"""")
        val reverse = Regex("""(?:content|href)="([^"]*)"[^<>]*itemprop="$propName"""")
        return (forward.findAll(html).map { it.groupValues[1] } + reverse.findAll(html).map { it.groupValues[1] }).toList()
    }

    /** One attribute out of a single tag's text. */
    private fun attr(
        tag: String,
        name: String,
    ): String? = Regex("""\s$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** HTML entities the site actually emits, decoded. */
    private fun unescape(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    /**
     * The keyword row as real tags: split, trimmed, entity-decoded, minus
     * the boilerplate tail every cell carries. The subject tags lead the
     * row, so what survives is exactly what a "more like this" query wants.
     */
    private fun parseTags(
        html: String,
        limit: Int,
    ): List<String> {
        val raw = metaValues(html, "keywords").firstOrNull() ?: return emptyList()
        return raw
            .split(",")
            .map { unescape(it.trim()) }
            .filter { it.isNotEmpty() && it.lowercase() !in BOILERPLATE_TAGS }
            .distinctBy { it.lowercase() }
            .take(limit)
    }

    /**
     * The listing grid. Each cell opens at an `associatedMedia` itemprop,
     * so the page splits there; the chunk before the first marker is page
     * chrome and is dropped, and every chunk is parsed independently — one
     * malformed cell degrades to itself, never to its neighbors.
     */
    fun parseGrid(
        html: String,
        maxTags: Int = MAX_TAGS,
    ): List<GridItem> =
        html
            .split("""itemprop="associatedMedia"""")
            .drop(1)
            .mapNotNull { chunk ->
                val originalUrl = metaValues(chunk, "contentUrl").firstOrNull() ?: return@mapNotNull null
                val detailUrl = metaValues(chunk, "url").firstOrNull() ?: return@mapNotNull null
                val id =
                    Regex("""big\.php\?i=(\d+)""").find(detailUrl)?.groupValues?.get(1)
                        ?: originalUrl.substringAfterLast('/').substringBefore('.').ifBlank { return@mapNotNull null }
                val thumbUrl = metaValues(chunk, "thumbnailUrl").firstOrNull() ?: return@mapNotNull null
                val tags = parseTags(chunk, maxTags)
                val title =
                    metaValues(chunk, "name").firstOrNull()?.let(::unescape)?.takeIf { it.isNotBlank() }
                        ?: tags.firstOrNull()
                GridItem(
                    id = id,
                    title = title,
                    thumbUrl = thumbUrl,
                    originalUrl = originalUrl,
                    tags = tags,
                )
            }

    /**
     * The detail record from a big.php page. The main wallpaper's block
     * (`itemprop="mainEntity"`) precedes the related cells in every captured
     * page, so first-match wins for its metas; the `main-content` img is
     * unambiguous by class. Returns null only when none of the identifying
     * shapes are present — a redesigned page, not a sparse one.
     */
    fun parseDetail(html: String): DetailRecord? {
        val originalUrl = metaValues(html, "contentUrl").firstOrNull()
        val mainImg = Regex("""<img[^<>]*class="main-content"[^<>]*>""").find(html)?.value
        val imgWidth = mainImg?.let { attr(it, "width") }?.toIntOrNull()
        val imgHeight = mainImg?.let { attr(it, "height") }?.toIntOrNull()
        val fileInfo = FileInfo.find(html)
        val width = imgWidth ?: fileInfo?.width
        val height = imgHeight ?: fileInfo?.height

        val author =
            metaValues(html, "author").firstOrNull()?.let(::unescape)?.takeIf { it.isNotBlank() }
        val rawTitle =
            mainImg?.let { attr(it, "title") }?.takeIf { it.isNotBlank() }
                ?: metaValues(html, "name").firstOrNull()?.takeIf { it.isNotBlank() }
        val title = cleanTitle(raw = rawTitle, author = author)
        return if (originalUrl == null && mainImg == null && fileInfo == null) {
            null
        } else {
            DetailRecord(
                originalUrl = originalUrl,
                previewUrl =
                    mainImg?.let { attr(it, "src") }?.takeIf { it.isNotBlank() }
                        ?: metaValues(html, "image").firstOrNull()?.takeIf { it.isNotBlank() },
                width = width,
                height = height,
                title = title,
                author = author,
                fileSizeBytes = fileInfo?.sizeBytes,
                tags = parseTags(html, MAX_TAGS),
                description =
                    metaValues(html, "caption description").firstOrNull()?.let(::unescape)?.takeIf { it.isNotBlank() },
                colors = parseColors(html),
            )
        }
    }

    /** `Download X Wallpaper | 3840x2400 | Wallpaper Abyss` shaped titles, cleaned. */
    private fun cleanTitle(
        raw: String?,
        author: String?,
    ): String? {
        if (raw == null) return null
        var title =
            raw
                .replace(Regex("""\s*\|\s*\d+x\d+\s*\|\s*Wallpaper Abyss\s*$"""), "")
                .removePrefix("Download ")
                .trim()
        if (author != null) {
            title = title.removeSuffix(" by $author").trim()
        }
        return title.ifBlank { null }
    }

    /** The site's color row anchors: `class='color-info …' > #010101 <`. */
    private fun parseColors(html: String): List<String> =
        Regex("""class=['"]color-info[^'"]*['"][^>]*>\s*#([0-9a-fA-F]{6})""")
            .findAll(html)
            .map { "#${it.groupValues[1].lowercase()}" }
            .distinct()
            .take(MAX_COLORS)
            .toList()

    /** The File Info box: `3840x2400  1.38 MB  JPG`, whitespace-run agnostic. */
    private data class FileInfo(
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
    ) {
        companion object {
            private val SHAPE =
                Regex("""title="File Info"[^>]*>\s*(\d+)x(\d+)\s+([\d.]+)\s*(B|KB|MB|GB)\b""")

            internal fun find(html: String): FileInfo? =
                SHAPE.find(html)?.let { match ->
                    val value = match.groupValues[3].toDoubleOrNull() ?: return null
                    val unit =
                        when (match.groupValues[4].uppercase()) {
                            "B" -> 1.0
                            "KB" -> 1024.0
                            "MB" -> 1024.0 * 1024
                            "GB" -> 1024.0 * 1024 * 1024
                            else -> return null
                        }
                    FileInfo(
                        width = match.groupValues[1].toIntOrNull() ?: return null,
                        height = match.groupValues[2].toIntOrNull() ?: return null,
                        sizeBytes = (value * unit).toLong(),
                    )
                }
        }
    }

    private const val MAX_TAGS = 6
    private const val MAX_COLORS = 10
}

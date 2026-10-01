package com.cloudimage.wallpaperaccess

import com.cloudimage.provider.api.Category

/**
 * Reads wallpaperaccess.com's server-rendered Semantic UI pages — the same
 * shapes its own browser UI is built from, captured live and pinned by the
 * fixture tests.
 *
 * The site is album-first and pagination-free: a category page
 * (`/cat/{slug}`) carries its COMPLETE album directory in one document
 * (150 cards for anime), an album page (`/{slug}`) carries the album's
 * COMPLETE wallpaper set (verified 70/70 and 99/99 live), and search
 * (`/search?q=`) carries every matching album card at once. Every screen
 * is one fetch, which is both the politeness floor and the parser's
 * simplicity: no cursors, no lazy-load walking, no AJAX.
 *
 * Every page also embeds the site's full category navigation (28
 * categories, each with its own emoji), so any fetch can refresh the
 * sidebar's list — and a baked-in copy in the provider keeps the sidebar
 * alive before the first fetch lands or when the network is down.
 */
internal object WallpaperAccessParser {
    /**
     * One album card as it sits in a category page, the homepage's
     * collections segment or the search results — the same markup in all
     * three, minus the search page's no-result apology (see
     * [hasNoResultsMarker]).
     */
    data class AlbumCard(
        val title: String,
        val slug: String,
        val coverId: String,
        val coverExt: String,
        val wallpaperCount: Int,
    )

    /** One wallpaper item of an album page's wall. */
    data class WallpaperItem(
        val id: String,
        val ext: String,
        val width: Int,
        val height: Int,
        val title: String?,
    )

    /** One category link of the site-wide navigation menu. */
    data class CategoryLink(
        val slug: String,
        val name: String,
        val emoji: String?,
    )

    /** The anchor block of one album card, DOTALL-spanning to its close. */
    private val albumBlock =
        Regex(
            """<div class="column collection_thumb">\s*<a title="([^"]+)"[^>]*href="/([a-z0-9-]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /** The cover image inside an album card (src, not the lazy data-src). */
    private val coverSrc = Regex("""src="/thumb/(\d+)\.(\w+)"""")

    /** The wallpaper count badge inside an album card. */
    private val countMarker = Regex("""<i class="image icon"></i>\s*(\d+)""")

    /** One wallpaper item: id, original extension, true dimensions, alt title. */
    private val wallpaperBlock =
        Regex(
            """<div id="(\d+)"[^>]*data-fullimg="/full/\1\.(\w+)"[^>]*data-or="(\d+)x(\d+)"[^>]*>.*?<img class="[^"]*thumb[^"]*"[^>]*alt="([^"]*)"""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    /** One category link of the navigation menu (web and mobile copies). */
    private val categoryLink =
        Regex(
            """<a href="/cat/([a-z0-9-]+)"\s+class="item"[^>]*>([^<]+)</a>""",
        )

    /** The search page's zero-result apology — its cards are suggestions, not results. */
    private val noResultsMarker = Regex("""Sorry, no wallpapers found for""")

    /** The " Wallpapers" suffix the card titles carry. */
    private val titleSuffix = Regex("""\s+Wallpapers$""")

    /** The alt text's resolution prefix and marketing suffix. */
    private val altPrefix = Regex("""^\d+x\d+\s+""")
    private val altSuffix = Regex("""\s+HD Wallpaper and Background Image$""")

    /**
     * The album cards of any card-carrying page — homepage, category,
     * search. Titles lose their " Wallpapers" suffix, covers keep their
     * original file extension (the thumb endpoint is extension-strict:
     * `.jpg` for a PNG original answers HTTP 415), and cards without a
     * usable cover drop. Slugs dedupe: the site sometimes renders its
     * mobile and web card sets into one document.
     */
    fun parseAlbumCards(html: String): List<AlbumCard> =
        albumBlock
            .findAll(html)
            .mapNotNull { match ->
                val title = match.groupValues[1].replace(titleSuffix, "").trim()
                val slug = match.groupValues[2]
                val body = match.groupValues[3]
                val cover = coverSrc.find(body) ?: return@mapNotNull null
                if (title.isBlank() || slug.isBlank()) return@mapNotNull null
                AlbumCard(
                    title = title,
                    slug = slug,
                    coverId = cover.groupValues[1],
                    coverExt = cover.groupValues[2].lowercase(),
                    wallpaperCount =
                        countMarker
                            .find(body)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?: 0,
                )
            }.distinctBy { it.slug }
            .toList()

    /**
     * True when the search page apologizes instead of answering — the
     * trending cards that follow it are the site's suggestions and must
     * never be served as results.
     */
    fun hasNoResultsMarker(html: String): Boolean = noResultsMarker.containsMatchIn(html)

    /**
     * The complete wallpaper set of an album page: original file ids with
     * their TRUE dimensions from `data-or` and cleaned alt titles. The
     * grid's own `src` values point at `/full/` — the site serves its
     * originals as thumbnails — so this re-points them at `/thumb/`, a
     * quarter of the bytes for the same picture.
     */
    fun parseWallpaperItems(html: String): List<WallpaperItem> =
        wallpaperBlock
            .findAll(html)
            .map { match ->
                WallpaperItem(
                    id = match.groupValues[1],
                    ext = match.groupValues[2].lowercase(),
                    width = match.groupValues[3].toIntOrNull() ?: 0,
                    height = match.groupValues[4].toIntOrNull() ?: 0,
                    title = cleanTitle(match.groupValues[5]),
                )
            }.distinctBy { it.id }
            .toList()

    /**
     * The site-wide category navigation, embedded in every page: the
     * emoji the site itself ships for the category and its display name.
     * The web and mobile menus duplicate every link — deduped by slug.
     */
    fun parseCategoryLinks(html: String): List<CategoryLink> =
        categoryLink
            .findAll(html)
            .map { match ->
                val text = match.groupValues[2].trim()
                // The emoji is the leading non-ASCII run ("💥 Anime");
                // a category without one still has its name.
                val emoji = text.takeWhile { it.code > 0x7F }
                val name = text.drop(emoji.length).trim()
                CategoryLink(
                    slug = match.groupValues[1],
                    name = name.ifBlank { match.groupValues[1] },
                    emoji = emoji.ifBlank { null },
                )
            }.distinctBy { it.slug }
            .toList()

    /** Cleans an alt title: "3840x2160 Attack On Titan HD Wallpaper and Background Image" -> "Attack On Titan". */
    private fun cleanTitle(alt: String): String? =
        alt
            .replace(altPrefix, "")
            .replace(altSuffix, "")
            .trim()
            .ifBlank { null }
}

/** Turns a navigation link into the contract's category. */
internal fun WallpaperAccessParser.CategoryLink.toCategory(): Category =
    Category(
        id = slug,
        name = name,
        iconEmoji = emoji,
    )

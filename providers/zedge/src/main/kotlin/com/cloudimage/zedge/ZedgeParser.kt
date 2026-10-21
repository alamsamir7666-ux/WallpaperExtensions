package com.cloudimage.zedge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads zedge.net's wallpaper pages out of the Next.js App Router RSC
 * flight stream — the shapes the site's own server renders, captured
 * from the live routes and pinned by the fixture tests.
 *
 * ## Why the flight stream and not the DOM
 *
 * Zedge's tile grids are CLIENT-HYDRATED: the HTML a plain GET receives
 * (and the document a WebView renders) carries NO tile anchors at all —
 * no `/wallpaper/{uuid}` hrefs, no tile `<img>` tags, only the app shell.
 * The wallpapers live in the flight stream every Next.js App Router page
 * embeds as `self.__next_f.push([1,"…"])` script chunks, whose
 * concatenated bodies carry the server's own component-and-data payload:
 *
 * - listings (`/wallpapers`, `/category/wallpapers/{slug}`) speak
 *   `initialData.pages[].items[]` — arrays of item records, each with
 *   its stable UUID `id`, `title`, `type` and a `meta` of signed
 *   `is.zobj.net` image renditions (`thumbUrl` the grid crop,
 *   `previewUrl` the bigger rendition the site itself displays) —
 *   and every `pages[]` element closes with a `next` field: the
 *   base64 keyset cursor (`_score|firstPublishedAt|id|_preference`)
 *   that continues the listing past its last item, the same marker
 *   the site's own "Load more" rides;
 * - search (`/find/{query}`) speaks `searchInitialData` — typed sections
 *   (`WALLPAPER`, `DYNAMIC_24H_WALLPAPER`, `RINGTONE`,
 *   `NOTIFICATION_SOUND`, `PROFILE`…), each with its own items; only the
 *   `WALLPAPER` section serves, so audio and live-wallpaper products
 *   never leak into a wallpaper feed;
 * - wallpaper pages (`/wallpaper/{uuid}`) speak an `initialData` record
 *   carrying the definitive content — the `h1`-grade `title`, the
 *   `description`, the site's own `tags` row, the artist `profile`, the
 *   canonical `shareUrl` and the `meta` renditions.
 *
 * The stream is the site's OWN SSR state — the same data its browser UI
 * hydrates from — so parsing it is reading the site the way it reads
 * itself: no class names, no CDN-host knowledge (image URLs are taken
 * verbatim), no DOM shape guesses. The flight chunks survive both fetch
 * lanes the host ladder uses: the plain GET's raw HTML and the WebView
 * lane's serialized DOM keep their script contents either way.
 *
 * ## The flat directory (no album paradigm)
 *
 * Zedge is a FLAT directory: every listing resolves straight to single
 * wallpapers behind stable UUID routes — there is no album or collection
 * layer anywhere in the grammar. The listing cursor (the base64 keyset
 * marker in `pages[].next`) is handed to the provider for the URL
 * continuation it addresses: the page-1 response carries the cursor to
 * page 2, and the provider asks the server for it as search params on
 * the same route. The grammar is guarded both ways: a server that
 * ignores the cursor re-serves the identical listing (the same `next`
 * marker comes back — the echo is detected, the answer is the honest
 * empty, never a repeat of the first page to fake depth), and an
 * advancing page whose items are all dups of what already served is
 * the honest empty too. [ZedgeParser] only reads what the stream
 * states; the continuation logic and its guards live in the provider.
 *
 * ## Search stays single-page (the honest answer)
 *
 * The mixed-content `searchInitialData` sections carry NO cursor
 * anywhere in the captured grammar — no `next` marker on any section —
 * so search parses one page and the provider reports `nextPage = null`
 * for it, the same honesty as before.
 *
 * ## Honesty rules
 *
 * - an item without a usable `id` or image rendition drops, never
 *   invents;
 * - a listing the site ships empty (or a page with no stream at all)
 *   parses to the honest zero;
 * - a detail page the site cannot answer (its own "couldn't find it"
 *   screen) carries no `shareUrl`-bearing record and reads as
 *   unrecognized — null, never a fabricated wallpaper;
 * - the detail page's related-items shell is an `initialData` with empty
 *   `pages` — it can never be mistaken for the wallpaper itself, and it
 *   never shadows the real record.
 */
internal object ZedgeParser {
    /**
     * One tile of any listing: the wallpaper's UUID (its stable id — the
     * detail route is `/wallpaper/{uuid}`), the tile's own grid crop,
     * the site's own bigger rendition as a working full before details
     * are ever fetched, and the item's title as the site states it.
     */
    data class GridItem(
        val id: String,
        val thumbUrl: String,
        val fullUrl: String,
        val title: String?,
    )

    /**
     * One listing page of the site's own SSR state: its wallpaper tiles
     * in the site's order, deduped by id, and the base64 keyset cursor
     * that continues the listing (`pages[].next`) — null when the site
     * states no continuation (the last page of the grid, a page with no
     * stream, a trimmed response). This is the raw material the
     * provider's cursor chain rides.
     */
    data class ListingPage(
        val items: List<GridItem>,
        val nextCursor: String?,
    )

    /**
     * The definitive record of a wallpaper page: the site's own title,
     * its description, its tag row, the artist it credits, its canonical
     * share URL, and its renditions — the grid crop and the bigger
     * preview the page itself displays.
     */
    data class DetailRecord(
        val id: String,
        val title: String,
        val description: String?,
        val tags: List<String>,
        val thumbUrl: String,
        val fullUrl: String,
        val author: String?,
        val shareUrl: String?,
    )

    /**
     * One flight chunk's opening shape: `self.__next_f.push([1,"` — the
     * body after it is a JSON string literal (quotes and backslashes
     * escaped), closed by its own unescaped quote and the `])` tail.
     * Walked with a hand scanner, not a regex: the bodies run tens of
     * kilobytes, and Java's regex engine recurses per alternation
     * repetition — a stack overflow waiting to happen. The scanner is
     * iterative, O(n), and exact about the same JSON string rules.
     */
    private const val PUSH_OPEN = "self.__next_f.push([1,\""

    private val json = Json { ignoreUnknownKeys = true }

    /** The site's one wallpaper item type — audio and live products never serve. */
    private const val WALLPAPER_TYPE = "WALLPAPER"

    /** The detail tags' honest cap. */
    private const val MAX_TAGS = 12

    // ------------------------------------------------------------ flight

    /**
     * The page's flight stream: every chunk's body, JSON-unescaped and
     * concatenated in page order — the same stream the site's own
     * hydration consumes. Chunks that fail to unescape (a serialization
     * the JSON reader cannot take) skip rather than fail the page.
     */
    internal fun flightOf(html: String): String {
        val stream = StringBuilder()
        var from = 0
        while (true) {
            val open = html.indexOf(PUSH_OPEN, from)
            if (open < 0) break
            val body = StringBuilder()
            var i = open + PUSH_OPEN.length
            var closed = false
            while (i < html.length) {
                val c = html[i]
                if (c == '\\' && i + 1 < html.length) {
                    body.append(c).append(html[i + 1])
                    i += 2
                } else if (c == '"') {
                    i++
                    closed = true
                    break
                } else {
                    body.append(c)
                    i++
                }
            }
            from = open + PUSH_OPEN.length
            if (!closed || !html.startsWith("])", i)) continue
            runCatching {
                (json.parseToJsonElement("\"$body\"") as? JsonPrimitive)
                    ?.content
                    ?.let(stream::append)
            }
        }
        return stream.toString()
    }

    // ------------------------------------------------------------ listings

    /**
     * The wallpaper items of a listing page (the landing, a category
     * page) — [parseListing] without the cursor, the shape the
     * provider's random shuffle feeds on. Kept because it is the
     * honest read of "just the tiles".
     */
    fun parseGrid(html: String): List<GridItem> = parseListing(html).items

    /**
     * One listing page whole: the tiles of the first `initialData`
     * carrying a non-empty `pages` array, in the site's own order,
     * deduped by id, together with the continuation cursor its last
     * `pages[]` element states. A page whose listings are all empty (or
     * that carries no stream at all) parses to the honest zero with no
     * cursor — and the related-items shell on detail pages (empty
     * `pages`) never reads as a listing.
     */
    fun parseListing(html: String): ListingPage {
        val flight = flightOf(html)
        val listing =
            valuesOf(flight, "initialData")
                .mapNotNull { it as? JsonObject }
                .filter { it.containsKey("pages") }
                .firstNotNullOfOrNull { candidate ->
                    candidate.takeIf { itemsOfListing(it).isNotEmpty() }
                } ?: return ListingPage(emptyList(), null)
        val items = itemsOfListing(listing).distinctBy { it.id }
        val pages = listing["pages"] as? JsonArray
        val lastPage = pages?.lastOrNull() as? JsonObject
        return ListingPage(items, lastPage?.stringOf("next"))
    }

    /**
     * The wallpaper items of a search page, scoped to the site's own
     * `WALLPAPER` section: the mixed-content `searchInitialData` array
     * also carries dynamic wallpapers, ringtones, notification sounds
     * and artist profiles, and none of them may ever serve as a
     * wallpaper. A search the site answers with audio only parses to
     * the honest zero; a page with no sections at all parses the same.
     */
    fun parseSearch(html: String): List<GridItem> {
        val flight = flightOf(html)
        return valuesOf(flight, "searchInitialData")
            .mapNotNull { it as? JsonArray }
            .flatMap { sections -> sections.mapNotNull { it as? JsonObject } }
            .filter { section -> section.stringOf("type") == WALLPAPER_TYPE }
            .flatMap { section ->
                (section["items"] as? JsonArray)
                    ?.mapNotNull { item -> itemOf(item) }
                    ?: emptyList()
            }.distinctBy { it.id }
    }

    // ------------------------------------------------------------- detail

    /**
     * The definitive record of a wallpaper page, or null when the page
     * does not carry one — the site's own "couldn't find it" screen
     * ships no `shareUrl`-bearing record and reads as unrecognized,
     * never as an empty wallpaper. The record's renditions are the
     * site's own: the grid crop for the thumb, the bigger preview the
     * page itself displays for the full.
     */
    fun parseDetail(html: String): DetailRecord? {
        val flight = flightOf(html)
        val record =
            valuesOf(flight, "initialData")
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.containsKey("shareUrl") }
                ?: return null
        val id = record.stringOf("id") ?: return null
        val title = record.stringOf("title") ?: return null
        val meta = record["meta"] as? JsonObject
        val thumb = meta?.stringOf("thumbUrl") ?: meta?.stringOf("previewUrl")
        val full = meta?.stringOf("previewUrl") ?: thumb
        val resolvedFull = full ?: return null
        val resolvedThumb = thumb ?: resolvedFull
        return DetailRecord(
            id = id,
            title = title,
            description = record.stringOf("description"),
            tags = tagsOf(record),
            thumbUrl = resolvedThumb,
            fullUrl = resolvedFull,
            author = (record["profile"] as? JsonObject)?.stringOf("name"),
            shareUrl = record.stringOf("shareUrl"),
        )
    }

    // ------------------------------------------------------------ helpers

    /** The items of one listing's `pages` array, wallpaper-typed only. */
    private fun itemsOfListing(listing: JsonObject): List<GridItem> =
        (listing["pages"] as? JsonArray)
            ?.flatMap { page ->
                ((page as? JsonObject)?.get("items") as? JsonArray)
                    ?.mapNotNull { item -> itemOf(item) }
                    ?: emptyList()
            } ?: emptyList()

    /**
     * One item record, or null when it is not a wallpaper or carries no
     * usable id or image — the site's own `type` field does the
     * filtering, so a ringtone or a live wallpaper can never serve.
     */
    private fun itemOf(element: kotlinx.serialization.json.JsonElement): GridItem? {
        val item = element as? JsonObject ?: return null
        if (item.stringOf("type") != WALLPAPER_TYPE) return null
        val id = item.stringOf("id") ?: return null
        val meta = item["meta"] as? JsonObject ?: return null
        val thumb = meta.stringOf("thumbUrl") ?: meta.stringOf("previewUrl") ?: return null
        val full = meta.stringOf("previewUrl") ?: thumb
        return GridItem(
            id = id,
            thumbUrl = thumb,
            fullUrl = full,
            title = item.stringOf("title"),
        )
    }

    /** The site's own tag row, decoded, deduped, capped. */
    private fun tagsOf(record: JsonObject): List<String> =
        (record["tags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(MAX_TAGS)
            ?: emptyList()

    /**
     * Every JSON object or array the flight carries right after an
     * occurrence of `"key":` — bracket-matched and parsed. Occurrences
     * inside string values (escaped, so not real keys) at worst yield
     * an unparseable slice that drops out; the shape filters in the
     * readers keep such noise from ever serving.
     */
    private fun valuesOf(
        flight: String,
        key: String,
    ): List<kotlinx.serialization.json.JsonElement> {
        val needle = "\"$key\":"
        val values = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var from = 0
        while (true) {
            val keyAt = flight.indexOf(needle, from)
            if (keyAt < 0) break
            from = keyAt + needle.length
            var start = from
            while (start < flight.length && flight[start].isWhitespace()) start++
            if (start >= flight.length) break
            val opener = flight[start]
            if (opener != '{' && opener != '[') continue
            val end = balancedEnd(flight, start) ?: continue
            runCatching { values += json.parseToJsonElement(flight.substring(start, end + 1)) }
        }
        return values
    }

    /**
     * The index of the bracket closing the object or array opening at
     * [start], honoring JSON strings and escapes — or null when the
     * stream truncates first.
     */
    private fun balancedEnd(
        text: String,
        start: Int,
    ): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return null
    }

    /** One object's string field, null when absent, blank, or JSON null. */
    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() && it != "null" }
}

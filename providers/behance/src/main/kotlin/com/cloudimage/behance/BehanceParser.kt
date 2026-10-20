package com.cloudimage.behance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One project card of a Behance search wall — the unit every grid, feed and
 * album is built from. [thumbUrl] is the cover's 404-grade webp copy,
 * [fullUrl] the cover original: a working wallpaper-grade URL before the
 * project page is ever fetched, the same working-original doctrine the
 * mordeo and hdwallpapers sources document.
 */
internal data class ProjectCard(
    val id: String,
    val name: String,
    val url: String,
    val thumbUrl: String,
    val fullUrl: String,
    val author: String?,
)

/** One search wall: the cards, the SSR cursor state, and the tag rail. */
internal data class SearchOutcome(
    val cards: List<ProjectCard>,
    val hasNextPage: Boolean,
    val endCursor: String?,
    val tagSuggestions: List<String>,
)

/**
 * One image module of a project page — Behance's own full-resolution copy
 * ("source" rendition, the uploaded original) with its TRUE dimensions, the
 * display copy for the grid, and the module id that pins it inside its
 * project. The site discloses the original's width on the source rendition
 * itself and the display copy's aspect on the module — the pair composes
 * the true resolution, stated by the site's own numbers alone.
 */
internal data class ModuleImage(
    val moduleId: String,
    val thumbUrl: String,
    val fullUrl: String,
    val width: Int?,
    val height: Int?,
)

/** The definitive record of a project page, re-read for [com.cloudimage.provider.api.WallpaperProvider.details]. */
internal data class ProjectRecord(
    val id: String,
    val name: String,
    val url: String,
    val owners: List<String>,
    val tags: List<String>,
    val images: List<ModuleImage>,
    val coverThumbUrl: String?,
    val coverFullUrl: String?,
)

/**
 * Reader of Behance's server-rendered state — the one large
 * `<script type="application/json">` block every Behance page embeds for
 * its Ember app. Pure functions over the page text, no network, no
 * mutation; the provider fetches, the parser reads.
 *
 * The state is navigated with JsonElement walks instead of DTO classes on
 * purpose: the block is a whole-app snapshot whose shape varies per page
 * type (search pages carry `search.projects.search`, project pages carry
 * `project.project`), and typed walks over the two known paths keep the
 * extension inside the repo's ABI surface — kotlin stdlib plus
 * kotlinx.serialization, nothing else — while staying total: every walk
 * answers null on a page that does not carry the expected subtree, and
 * every picker degrades to the next rendition instead of inventing data.
 *
 * ## Rendition grammar (verified live)
 *
 * Cover URLs live under `mir-s3-cdn-cf.behance.net/projects/<segment>/...`
 * and module URLs under `.../project_modules/<segment>/...`. The segment is
 * the size name and it is the ONLY reliable key: `allAvailable` order varies
 * between pages (webp sometimes first, sometimes last), so pickers key by
 * segment, never by position.
 *
 * - covers: `original`/`original_webp` (the full cover), `404`, `202`, `115`
 *   with `_webp` twins — thumb preference 404 first for grid-sized weight;
 * - modules: `source` (the uploaded original — the wallpaper), `max_3840`,
 *   `2800`, `1400`, `fs`, `hd`, `max_1200`, `max_632`, `disp` (600-wide
 *   display copy) — full preference source first, thumb preference disp.
 */
internal object BehanceParser {
    private val stateBlock = Regex("""<script type="application/json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    private val json = Json { ignoreUnknownKeys = true }

    // Rendition preferences, first match wins; webp first where both exist
    // (smaller bytes for the same size), with the plain twin as fallback.
    private val coverThumbPreference =
        listOf("404_webp", "404", "202_webp", "202", "115_webp", "115", "max_808_webp", "original_webp", "original")
    private val coverFullPreference = listOf("original_webp", "original", "max_808_webp", "808", "404_webp", "404")
    private val moduleThumbPreference =
        listOf("disp_webp", "disp", "max_632_webp", "max_632", "hd_webp", "hd", "fs_webp", "fs")
    private val moduleFullPreference =
        listOf("source", "max_3840", "2800", "1400", "fs", "hd", "max_1200", "max_632", "disp")

    /**
     * The largest application/json block of the page — Behance embeds a
     * tiny assets config and the (hundreds-of-KB) app state; the state is
     * always the big one, on every page type observed.
     */
    internal fun stateTextOf(html: String): String? =
        stateBlock
            .findAll(html)
            .maxByOrNull { it.groupValues[1].length }
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotBlank() }

    /** The search wall of a search page, or null when the page carries none. */
    fun parseSearch(html: String): SearchOutcome? {
        val root = stateOf(html) ?: return null
        val search = root["search"] as? JsonObject ?: return null
        val projects = search["projects"] as? JsonObject ?: return null
        val results = projects["search"] as? JsonObject ?: return null
        val nodes = results["nodes"] as? JsonArray ?: return null
        val pageInfo = results["pageInfo"] as? JsonObject
        return SearchOutcome(
            cards = nodes.mapNotNull(::cardOf),
            hasNextPage = (pageInfo?.get("hasNextPage") as? JsonPrimitive)?.content == "true",
            endCursor = (pageInfo?.get("endCursor") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
            tagSuggestions =
                (projects["tagSuggestions"] as? JsonArray)
                    ?.mapNotNull { tag -> ((tag as? JsonObject)?.get("concatenatedText") as? JsonPrimitive)?.content }
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
        )
    }

    /**
     * The project record of a gallery page, or null when the page carries
     * none (a challenge page, an error page, a profile page).
     */
    fun parseProject(html: String): ProjectRecord? {
        val root = stateOf(html) ?: return null
        val shell = root["project"] as? JsonObject ?: return null
        val project = shell["project"] as? JsonObject ?: return null
        val id = project.primitive("id") ?: return null
        return ProjectRecord(
            id = id,
            name = project.primitive("name").orEmpty(),
            url = project.primitive("url").orEmpty(),
            owners =
                (project["owners"] as? JsonArray)
                    ?.mapNotNull { owner -> (owner as? JsonObject)?.primitive("displayName") }
                    .orEmpty(),
            tags =
                (project["tags"] as? JsonArray)
                    ?.mapNotNull { tag -> (tag as? JsonObject)?.primitive("title")?.trim()?.takeIf(String::isNotEmpty) }
                    .orEmpty(),
            images = (project["modules"] as? JsonArray)?.mapNotNull(::imageOf).orEmpty(),
            coverThumbUrl = coverOf(project, coverThumbPreference),
            coverFullUrl = coverOf(project, coverFullPreference),
        )
    }

    // ------------------------------------------------------------- pickers

    /** One search node into a card; a node without usable covers is dropped. */
    private fun cardOf(node: JsonElement): ProjectCard? {
        val obj = node as? JsonObject ?: return null
        val id = obj.primitive("id") ?: return null
        val thumb = coverOf(obj, coverThumbPreference) ?: return null
        return ProjectCard(
            id = id,
            name = obj.primitive("name").orEmpty(),
            url = obj.primitive("url").orEmpty(),
            thumbUrl = thumb,
            fullUrl = coverOf(obj, coverFullPreference) ?: thumb,
            author =
                (obj["owners"] as? JsonArray)
                    ?.firstOrNull()
                    ?.let { it as? JsonObject }
                    ?.let { owner ->
                        owner.primitive("displayName") ?: owner.primitive("username")
                    },
        )
    }

    /**
     * One project module into an image: only modules carrying an
     * `imageSizes.allAvailable` array qualify (TextModule and embeds carry
     * none — the module filter that keeps video-only prose projects out of
     * the album walls). The full is the `source` rendition — the uploaded
     * original — falling back down the size ladder when a project omits it;
     * the thumb is the 600-wide display copy.
     *
     * Dimensions: the source rendition discloses the original's own width,
     * the module's width/height carry the display copy's TRUE aspect — when
     * both speak, the aspect composes with the original's width into the
     * original's resolution (2160x2160, 4500x3000), the site's own two
     * numbers, never an invented pair. A rendition without a width keeps
     * the display pair; a module carrying neither keeps null.
     */
    private fun imageOf(module: JsonElement): ModuleImage? {
        val obj = module as? JsonObject ?: return null
        val sizes = obj["imageSizes"] as? JsonObject ?: return null
        val renditions = renditionsOf(sizes["allAvailable"] as? JsonArray) ?: return null
        val full = renditions.pick(moduleFullPreference) ?: return null
        val moduleWidth = obj.int("width")
        val moduleHeight = obj.int("height")
        val width = full.width ?: moduleWidth
        val height =
            when {
                full.width != null && moduleWidth != null && moduleHeight != null && moduleWidth > 0 ->
                    full.width * moduleHeight / moduleWidth
                moduleWidth != null && moduleHeight != null -> moduleHeight
                else -> null
            }
        return ModuleImage(
            moduleId = obj.primitive("id") ?: "",
            thumbUrl = renditions.pick(moduleThumbPreference)?.url ?: full.url,
            fullUrl = full.url,
            width = width,
            height = height,
        )
    }

    /**
     * The cover pickers shared by search cards and project pages: keys the
     * `allAvailable` renditions by their URL segment, then applies the
     * preference ladder.
     */
    private fun coverOf(
        obj: JsonObject,
        preference: List<String>,
    ): String? = renditionsOf((obj["covers"] as? JsonObject)?.get("allAvailable") as? JsonArray)?.pick(preference)?.url

    /** One size the site generates, keyed by its URL segment. */
    internal data class Rendition(
        val url: String,
        val width: Int?,
    )

    /**
     * The rendition table: segment name -> rendition, keyed off the path
     * segment after `/projects/` or `/project_modules/` (for example
     * `.../project_modules/source/xyz.png` -> "source").
     */
    private fun renditionsOf(array: JsonArray?): Map<String, Rendition>? {
        if (array == null) return null
        val table =
            buildMap {
                array.forEach { entry ->
                    val renditionObj = entry as? JsonObject ?: return@forEach
                    val url = renditionObj.primitive("url") ?: return@forEach
                    // Both path families key their size off one segment:
                    // .../projects/<segment>/... and .../project_modules/<segment>/...
                    // — and "project_modules" contains no "/projects/", so the
                    // families are tried independently, module family first.
                    val tail =
                        url
                            .substringAfter("/project_modules/", missingDelimiterValue = "")
                            .ifBlank {
                                url.substringAfter("/projects/", missingDelimiterValue = "")
                            }
                    val segment = tail.substringBefore('/')
                    if (segment.isNotBlank()) put(segment, Rendition(url, renditionObj.int("width")))
                }
            }
        return table.takeIf { it.isNotEmpty() }
    }

    private fun Map<String, Rendition>.pick(preference: List<String>): Rendition? =
        preference.firstNotNullOfOrNull { this[it] } ?: values.firstOrNull()

    // ----------------------------------------------------------- json walk

    private fun stateOf(html: String): JsonObject? =
        stateTextOf(html)?.let { text ->
            runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        }

    private fun JsonObject.primitive(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.int(key: String): Int? = primitive(key)?.toIntOrNull()
}

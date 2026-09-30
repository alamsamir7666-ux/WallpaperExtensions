package com.cloudimage.hdqwalls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Google Programmable Search tier of [HdqWallsWallpaperProvider] — the
 * extractor half of the site's own search fallback, read keylessly.
 *
 * ## Why this exists
 *
 * hdqwalls.com's search is a two-layer system. Its own database answers
 * first; when the database has NO match for a query (verified live: a
 * search for `indian actress` answers zero grid cells and no pagination
 * bar), the site's page embeds a Google Programmable Search Engine —
 * `<gcse:search>` fed by `cse.google.com/cse.js?cx=…` — and shows Google's
 * results for the same query inside the site's chrome. This object reads
 * that same Google tier, so a DB-miss query still answers with wallpapers
 * instead of an empty grid.
 *
 * ## How it works, keylessly
 *
 * The CSE element API needs no API key — only the engine ID (visible in
 * the site's own page source) and a short-lived bootstrap token:
 *
 * 1. `cse.google.com/cse.js?cx={id}` answers a few KB of JS whose config
 *    blob carries `"cse_token": "{base64}:{epoch-ms}"` — minted per
 *    bootstrap, minutes-lived.
 * 2. `cse.google.com/cse/element/v1?cx={id}&q={query}&start={offset}` +
 *    that token answers the engine's web results as JSON — the exact call
 *    the site's embedded element makes (and the reason the site's Web tab
 *    shows results while its Images tab does not: this engine indexes
 *    pages, not image files).
 *
 * The token is fetched fresh per fallback invocation rather than cached:
 * it is minutes-lived, the bootstrap is a few KB, and the fallback only
 * fires on a DB miss — one small request is cheaper than staleness bugs.
 * Google rate-limits the results endpoint from flagged (datacenter)
 * networks; the provider treats every failure here as "tier failed" and
 * degrades to the per-word site search, never an error.
 *
 * ## Parsing posture
 *
 * Same house rules as [HdqWallsParser]: stdlib and kotlinx.serialization
 * only (the host keeps those unrenamed for the DexClassLoader; anything
 * else dies at load time), regex and JSON navigation over the response,
 * every reader total — bad input yields nulls and empty lists, never
 * exceptions. The response may arrive JSONP-wrapped (an `O_o` banner, a
 * callback name, the object, a semicolon) or as bare JSON; the reader
 * accepts either by slicing to the outermost braces.
 */
internal object HdqWallsCseSearch {
    /** One web result of the engine — a hdqwalls page Google indexed. */
    data class CseResult(
        /** The result's absolute page URL, e.g. `https://hdqwalls.com/{slug}`. */
        val pageUrl: String,
        /** The unformatted result title, `- hdqwalls` branding still attached. */
        val title: String,
        /**
         * The rich-snippet thumbnail when the response volunteers one —
         * often the page's `og:image`. Verified as a site CDN URL before
         * any use; Google-proxied previews (`encrypted-tbn…`) are kept out
         * by [HdqWallsParser.siteOriginalUrl].
         */
        val imageUrl: String?,
    )

    /** One page of the engine's answer. */
    data class CsePage(
        val results: List<CseResult>,
        /**
         * The `start` offset of the next result page, read from the
         * cursor — null on the last page, exactly like the site's own
         * pagination bar semantics.
         */
        val nextStart: Int?,
    )

    /** The engine the site embeds on every DB-miss search (captured live). */
    const val ENGINE_ID = "partner-pub-9257850376806437:3940322700"

    /** The bootstrap the site's own page loads; answers the engine config blob. */
    const val BOOTSTRAP_URL = "https://cse.google.com/cse.js?cx=$ENGINE_ID"

    /** The element API that serves the engine's web results. */
    private const val RESULTS_URL = "https://cse.google.com/cse/element/v1"

    /** Results per page — the element's own default, one request per page. */
    const val PAGE_SIZE = 10

    /** The bootstrap config's token key. Not `token` — `cse_token`. */
    private val TOKEN = Regex(""""cse_token"\s*:\s*"([^"]+)"""")

    /** The trailing branding a result title carries (verified live). */
    private val SITE_BRANDING = Regex("""\s*[-|]\s*hdqwalls\s*$""", RegexOption.IGNORE_CASE)

    /** JSON facade; unknown keys ignored, navigation wrapped by callers. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The bootstrap token from the engine's config blob: `"cse_token":
     * "{opaque}:{epoch-ms}"`. Null on any other shape — the tier then
     * fails and the provider degrades.
     */
    fun extractToken(bootstrapJs: String): String? = TOKEN.find(bootstrapJs)?.groupValues?.get(1)

    /**
     * The results call for one page: `start` is a zero-based result offset
     * (page N begins at `(N-1) * [PAGE_SIZE]`), the engine ID arrives
     * form-encoded (`:` as `%3A`, as the element itself sends it), and the
     * freshly minted bootstrap token rides last.
     */
    fun resultsUrl(
        encodedQuery: String,
        start: Int,
        token: String,
    ): String =
        RESULTS_URL +
            "?cx=" + java.net.URLEncoder.encode(ENGINE_ID, Charsets.UTF_8.name()) +
            "&q=$encodedQuery" +
            "&num=$PAGE_SIZE" +
            "&start=$start" +
            "&safe=off" +
            "&cse_lang=en" +
            "&origin=unknown" +
            "&client=google-csse" +
            "&ie=utf-8" +
            "&oe=utf-8" +
            "&token=$token"

    /**
     * The engine's answer, JSONP-wrapped or bare, to a [CsePage]: every
     * `results` entry with its URL, unformatted title and optional
     * rich-snippet image, plus the cursor's next `start` offset. Null when
     * the body parses to nothing usable — transport callers treat that
     * exactly like a transport failure.
     */
    fun parseResults(
        body: String,
        start: Int,
    ): CsePage? {
        val payload = stripJsonp(body) ?: return null
        val element =
            runCatching { json.parseToJsonElement(payload) }.getOrNull()
                ?: return null
        val results =
            runCatching {
                val array = element.jsonObject["results"]?.jsonArray ?: return@runCatching null
                array.mapNotNull { entry ->
                    val item = entry.jsonObject
                    val url = item["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val thumb = item["richSnippet"]?.jsonObject?.get("cseThumbnail")?.jsonObject
                    CseResult(
                        pageUrl = url,
                        title = item["titleNoFormatting"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        imageUrl = thumb?.get("src")?.jsonPrimitive?.contentOrNull,
                    )
                }
            }.getOrNull() ?: return null
        val nextStart =
            runCatching {
                val cursor = element.jsonObject["cursor"]?.jsonObject ?: return@runCatching null
                val pages = cursor["pages"]?.jsonArray ?: emptyList()
                val starts =
                    pages.mapNotNull { page ->
                        page.jsonObject["start"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.toIntOrNull()
                    }
                starts.filter { it > start }.minOrNull()
            }.getOrNull()
        return CsePage(results, nextStart)
    }

    /**
     * A result URL to its wallpaper page slug, via the site parser's own
     * reader — anything not a hdqwalls.com page (Google sometimes appends
     * query-string cross-links) answers null and the item drops.
     */
    fun pageSlug(resultUrl: String): String? = HdqWallsParser.pageSlug(resultUrl)

    /**
     * A result title to a display title: the `- hdqwalls` branding off
     * first (verified live: `… HD Indian Celebrities 4k - hdqwalls`), then
     * the site parser's trailing `Wallpaper` strip — the same cleanup a
     * grid title gets.
     */
    fun cleanTitle(title: String): String = SITE_BRANDING.replace(title.trim(), "").trim().let(HdqWallsParser::cleanTitle)

    /**
     * The JSONP envelope off: the payload is everything from the first
     * `{` to the last `}` — which is the JSON object itself whether the
     * body arrived as a callback-wrapped JSONP document or as bare JSON.
     * Null when no object is present at all.
     */
    private fun stripJsonp(body: String): String? {
        val from = body.indexOf('{')
        val to = body.lastIndexOf('}')
        if (from < 0 || to <= from) return null
        return body.substring(from, to + 1)
    }
}

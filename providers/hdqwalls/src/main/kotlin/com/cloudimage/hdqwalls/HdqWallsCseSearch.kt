package com.cloudimage.hdqwalls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

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
 * 2. `cse.google.com/cse/element/v1` answers the engine's web results —
 *    requested exactly the way the site's embedded element requests it
 *    (read from the element's own shipped `cse_element__en.js`):
 *    `rsz=filtered_cse`, `num=10`, `hl=en`, `source=gcsc`, the engine id,
 *    the query, the safe setting, the token as `cse_tok` (NOT `token` —
 *    the element's own parameter name), the bootstrap's
 *    `cselibv`/`exp`/`fexp` when present, the result offset, the
 *    embedding page as `rurl`, and a JSONP callback — so the answer
 *    arrives envelope-wrapped, which the reader strips (and the reason
 *    the site's Web tab shows results while its Images tab does not:
 *    this engine indexes pages, not image files).
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

    /**
     * The engine config the bootstrap answers — everything the element's
     * own results request rides besides the query itself. The token is
     * required (a bootstrap without it answers no config); the version
     * and experiment flags are optional passengers the element forwards
     * when its config volunteers them.
     */
    data class EngineConfig(
        /** The bootstrap's `cse_token` — minutes-lived, minted per bootstrap. */
        val cseToken: String,
        /** The bootstrap's `cselibVersion`, forwarded as `cselibv`. */
        val cselibVersion: String?,
        /** The bootstrap's `exp` array, comma-joined. */
        val exp: String?,
        /** The bootstrap's `fexp` array, comma-joined. */
        val fexp: String?,
    )

    /** The engine the site embeds on every DB-miss search (captured live). */
    const val ENGINE_ID = "partner-pub-9257850376806437:3940322700"

    /** The bootstrap the site's own page loads; answers the engine config blob. */
    const val BOOTSTRAP_URL = "https://cse.google.com/cse.js?cx=$ENGINE_ID"

    /** The element API that serves the engine's web results. */
    private const val RESULTS_URL = "https://cse.google.com/cse/element/v1"

    /** Results per page — the element's own default for `filtered_cse`, one request per page. */
    const val PAGE_SIZE = 10

    /** The site's configured result set size — deduplicated results. */
    private const val RESULT_SET_SIZE = "filtered_cse"

    /** The element's fixed language and source identifiers. */
    private const val LANGUAGE = "en"
    private const val SOURCE = "gcsc"

    /**
     * The safe setting: the site's own element activates safe search; the
     * provider keeps it off for recall — a result only matters once it
     * resolves to a hdqwalls wallpaper page, so the filter costs nothing.
     */
    private const val SAFE = "off"

    /** The embedding page the element reports — the site's search page. */
    private const val SEARCH_PAGE = "https://hdqwalls.com/search"

    /**
     * The JSONP callback, the element's own scheme: `google.search.cse.`
     * + `api` + random digits (`Math.random() * 2E4` in the element's own
     * code). The envelope is stripped by [parseResults], never evaluated —
     * the name just has to look like one of the element's.
     */
    private const val CALLBACK_PREFIX = "google.search.cse.api"
    private const val CALLBACK_SPACE = 20_000

    /** The bootstrap config's token key. Not `token` — `cse_token`. */
    private val TOKEN = Regex(""""cse_token"\s*:\s*"([^"]+)"""")

    /** The bootstrap config's element library version key. */
    private val CSELIB_VERSION = Regex(""""cselibVersion"\s*:\s*"([^"]+)"""")

    /** The bootstrap config's experiment arrays — strings and numbers respectively. */
    private val EXP = Regex(""""exp"\s*:\s*\[([^\]]*)\]""")
    private val FEXP = Regex(""""fexp"\s*:\s*\[([^\]]*)\]""")

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
     * The engine config out of the bootstrap's JS: the token (required),
     * plus the version and experiment flags the element forwards on its
     * results requests. Null when the token is missing — the tier then
     * fails and the provider degrades.
     */
    fun parseBootstrap(bootstrapJs: String): EngineConfig? {
        val token = extractToken(bootstrapJs) ?: return null
        return EngineConfig(
            cseToken = token,
            cselibVersion = CSELIB_VERSION.find(bootstrapJs)?.groupValues?.get(1),
            exp = csv(EXP.find(bootstrapJs)?.groupValues?.get(1)),
            fexp = csv(FEXP.find(bootstrapJs)?.groupValues?.get(1)),
        )
    }

    /** A config array's body (`"a", "b"` or `1, 2`) to a comma-joined string, quotes stripped. */
    private fun csv(body: String?): String? =
        body
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",")

    /**
     * The results call for one page, the wire shape the site's own element
     * sends (read from its shipped `cse_element__en.js`): `rsz` and `num`
     * from the site's config, `hl` and `source=gcsc` fixed by the element,
     * the engine id and the token form-encoded (the token is base64 — a
     * raw `+` would read as a space; and its parameter name is `cse_tok`,
     * NOT `token`), the bootstrap's `cselibv`/`exp`/`fexp` forwarded when
     * present, the zero-based result offset in `start` (page N begins at
     * `(N-1) * [PAGE_SIZE]`), the embedding search page as `rurl`, and the
     * JSONP callback last — the answer arrives envelope-wrapped, which
     * [parseResults] strips. Every value rides percent-encoded, exactly
     * as the element's own URLSearchParams encodes them.
     */
    fun resultsUrl(
        encodedQuery: String,
        start: Int,
        config: EngineConfig,
    ): String =
        RESULTS_URL +
            "?rsz=$RESULT_SET_SIZE" +
            "&num=$PAGE_SIZE" +
            "&hl=$LANGUAGE" +
            "&source=$SOURCE" +
            "&cx=" + enc(ENGINE_ID) +
            "&q=$encodedQuery" +
            "&safe=$SAFE" +
            "&cse_tok=" + enc(config.cseToken) +
            (config.cselibVersion?.let { "&cselibv=" + enc(it) } ?: "") +
            (config.exp?.let { "&exp=" + enc(it) } ?: "") +
            (config.fexp?.let { "&fexp=" + enc(it) } ?: "") +
            "&start=$start" +
            "&rurl=" + enc("$SEARCH_PAGE?q=$encodedQuery") +
            "&callback=$CALLBACK_PREFIX" + Random.nextInt(CALLBACK_SPACE)

    /** Query encoding, matching the element's URLSearchParams: `:` as `%3A`, `+` as `%2B`. */
    private fun enc(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

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
     * reader — anything not a hdqwalls.com WALLPAPER page answers null and
     * the item drops: foreign pages (Google sometimes appends query-string
     * cross-links) and the site's own listing pages alike, whose plural
     * `-wallpapers` slugs the reader rejects (see
     * [HdqWallsParser.pageSlug]).
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

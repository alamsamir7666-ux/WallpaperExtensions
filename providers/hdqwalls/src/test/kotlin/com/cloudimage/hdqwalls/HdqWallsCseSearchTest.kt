package com.cloudimage.hdqwalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google CSE tier's parsing half, over shapes captured from the live
 * flow: the bootstrap config blob's `cse_token` (the key is `cse_token`,
 * NOT `token` — captured from the real `cse.js`) and its `cselibVersion`/
 * `exp`/`fexp` passengers, the element API's JSONP-wrapped and bare-JSON
 * answers, the cursor's page offsets, and the result-to-wallpaper mapping
 * readers. The JSONP fixture mirrors what the element answers on the wire
 * (including its `O_o` banner, callback wrapper and `\u0000cc`-style
 * escapes); every defensive edge — Google's rate-limit apology page, empty
 * bodies, foreign hosts, the listing pages its web results interleave —
 * degrades to nulls, never exceptions.
 */
class HdqWallsCseSearchTest {
    /** The bootstrap config blob, trimmed from the live cse.js capture. */
    private val bootstrapJs =
        """
        (function(){var relativeUrl='/cse.js?cx=partner-pub-9257850376806437:3940322700';})();(function(){var gcse=document.createElement('script');gcse.async=true;gcse.src=relativeUrl;})();
        {
          "cx": "partner-pub-9257850376806437:3940322700",
          "cse_token": "AHbIdTg_d1nKRAMULV0AfMi-g_HA:1790741990673",
          "isHostedPage": false,
          "cseLang": "en",
          "exp": ["cc", "sps", "esbfa"],
          "cselibVersion": "3735a6ee3000c0cb",
          "fexp": [121877337, 122056044, 121877336, 122056045],
          "searchbox": {"backgroundColor": "#FFFFFF"},
          "theme": "light"
        }
        """.trimIndent()

    /** The element API's answer, JSONP-wrapped as the element receives it. */
    private val jsonpResults =
        """
        /*O_o*/
        google.search.cse.api682738492({
         "results": [
          {"GsearchResultClass": "GwebSearch",
           "url": "https://hdqwalls.com/beautiful-indian-actress-wallpaper",
           "escapedUrl": "https://hdqwalls.com/beautiful-indian-actress-wallpaper",
           "title": "Beautiful Indian Actress Wallpaper, HD Indian Celebrities 4k - hdqwalls",
           "titleNoFormatting": "Beautiful Indian Actress Wallpaper, HD Indian Celebrities 4k - hdqwalls",
           "content": "Download Beautiful Indian Actress Wallpaper, Indian Celebrities..."},
          {"GsearchResultClass": "GwebSearch",
           "url": "https://hdqwalls.com/indian-actress-smile-wallpaper",
           "title": "Indian Actress Smile Wallpaper - hdqwalls",
           "titleNoFormatting": "Indian Actress Smile Wallpaper - hdqwalls",
           "richSnippet": {"cseThumbnail": {"src": "https://images.hdqwalls.com/wallpapers/bthumb/indian-actress-smile-qq.jpg", "width": "200"}},
           "content": "Download Indian Actress Smile Wallpaper..."},
          {"GsearchResultClass": "GwebSearch",
           "url": "https://www.google.com/search?q=cross+link",
           "title": "cross link",
           "titleNoFormatting": "cross link"}
         ],
         "cursor": {"pages": [{"start": "0", "label": "1"}, {"start": "10", "label": "2"}, {"start": "20", "label": "3"}],
          "estimatedResultCount": "3560",
          "moreResultsUrl": "https://cse.google.com/cse?cx=partner-pub-9257850376806437:3940322700"}
        });
        """.trimIndent()

    // ------------------------------------------------------------- bootstrap

    @Test
    fun `extractToken reads the cse_token out of the bootstrap blob`() {
        assertEquals("AHbIdTg_d1nKRAMULV0AfMi-g_HA:1790741990673", HdqWallsCseSearch.extractToken(bootstrapJs))
    }

    @Test
    fun `extractToken answers null on foreign or missing configs`() {
        assertNull(HdqWallsCseSearch.extractToken("no config blob here"))
        assertNull(HdqWallsCseSearch.extractToken("""{"cx": "partner-pub"}"""))
    }

    @Test
    fun `parseBootstrap reads the engine config out of the bootstrap blob`() {
        val config = HdqWallsCseSearch.parseBootstrap(bootstrapJs)!!

        assertEquals("AHbIdTg_d1nKRAMULV0AfMi-g_HA:1790741990673", config.cseToken)
        assertEquals("3735a6ee3000c0cb", config.cselibVersion)
        assertEquals("cc,sps,esbfa", config.exp)
        assertEquals("121877337,122056044,121877336,122056045", config.fexp)
    }

    @Test
    fun `parseBootstrap answers null without a token and tolerates missing passengers`() {
        assertNull(HdqWallsCseSearch.parseBootstrap("no config blob here"))
        assertNull(HdqWallsCseSearch.parseBootstrap("""{"cx": "partner-pub"}"""))
        // The version and experiment flags forward only when the config
        // volunteers them — a token-only bootstrap still parses.
        val bare = HdqWallsCseSearch.parseBootstrap("""{"cse_token": "AHbIdT:1"}""")!!
        assertEquals("AHbIdT:1", bare.cseToken)
        assertNull(bare.cselibVersion)
        assertNull(bare.exp)
        assertNull(bare.fexp)
    }

    // --------------------------------------------------------------- results

    @Test
    fun `parseResults reads the JSONP-wrapped element answer`() {
        val page = HdqWallsCseSearch.parseResults(jsonpResults, 0)!!

        assertEquals(3, page.results.size)
        val first = page.results.first()
        assertEquals("https://hdqwalls.com/beautiful-indian-actress-wallpaper", first.pageUrl)
        assertEquals("Beautiful Indian Actress Wallpaper, HD Indian Celebrities 4k - hdqwalls", first.title)
        assertNull(first.imageUrl)
        // The rich-snippet thumbnail rides along when Google volunteers it.
        assertEquals(
            "https://images.hdqwalls.com/wallpapers/bthumb/indian-actress-smile-qq.jpg",
            page.results[1].imageUrl,
        )
    }

    @Test
    fun `parseResults rides the cursor for pagination`() {
        val page1 = HdqWallsCseSearch.parseResults(jsonpResults, 0)!!
        assertEquals(10, page1.nextStart)
        val page2 = HdqWallsCseSearch.parseResults(jsonpResults, 10)!!
        assertEquals(20, page2.nextStart)
        // Past the last cursor entry: no next page — the element's stop signal.
        val last = HdqWallsCseSearch.parseResults(jsonpResults, 20)!!
        assertNull(last.nextStart)
    }

    @Test
    fun `parseResults accepts bare JSON without the JSONP envelope`() {
        val body =
            """
            {"results": [{"url": "https://hdqwalls.com/indian-actress-smile-wallpaper", "titleNoFormatting": "Indian Actress Smile Wallpaper - hdqwalls"}],
             "cursor": {"pages": [{"start": "0"}]}}
            """.trimIndent()
        val page = HdqWallsCseSearch.parseResults(body, 0)!!
        assertEquals(1, page.results.size)
        assertNull(page.nextStart)
    }

    @Test
    fun `parseResults degrades to null on unusable bodies`() {
        // Google's rate-limit apology page — the shape the 403 tier-failure
        // actually carries when it is not a bare status code.
        assertNull(HdqWallsCseSearch.parseResults("<html><body>Sorry...</body></html>", 0))
        assertNull(HdqWallsCseSearch.parseResults("", 0))
        assertNull(HdqWallsCseSearch.parseResults("no object at all", 0))
        assertNull(HdqWallsCseSearch.parseResults("{broken json", 0))
    }

    // ---------------------------------------------------------------- mapping

    @Test
    fun `pageSlug maps hdqwalls wallpaper pages only`() {
        assertEquals(
            "beautiful-indian-actress-wallpaper",
            HdqWallsCseSearch.pageSlug("https://hdqwalls.com/beautiful-indian-actress-wallpaper"),
        )
        assertEquals("gone-wallpaper", HdqWallsCseSearch.pageSlug("https://hdqwalls.com/gone-wallpaper?utm=x"))
        assertNull(HdqWallsCseSearch.pageSlug("https://www.google.com/search?q=cross+link"))
        assertNull(HdqWallsCseSearch.pageSlug("not a url"))
        // The listing shapes Google's web results interleave: tag, category
        // and resolution pages use the PLURAL suffix, the search page none.
        assertNull(HdqWallsCseSearch.pageSlug("https://hdqwalls.com/girls-wallpapers"))
        assertNull(HdqWallsCseSearch.pageSlug("https://hdqwalls.com/celebrities-wallpapers"))
        assertNull(HdqWallsCseSearch.pageSlug("https://hdqwalls.com/1080x1920-resolution-wallpapers"))
        assertNull(HdqWallsCseSearch.pageSlug("https://hdqwalls.com/search?q=indian+actress"))
        assertNull(HdqWallsCseSearch.pageSlug("https://hdqwalls.com/category/anime-wallpapers"))
    }

    @Test
    fun `cleanTitle strips the site branding then the wallpaper suffix`() {
        assertEquals("Indian Actress Smile", HdqWallsCseSearch.cleanTitle("Indian Actress Smile Wallpaper - hdqwalls"))
        // A mid-title Wallpaper word is content, not a suffix — it stays.
        assertEquals(
            "Beautiful Indian Actress Wallpaper, HD Indian Celebrities 4k",
            HdqWallsCseSearch.cleanTitle("Beautiful Indian Actress Wallpaper, HD Indian Celebrities 4k - hdqwalls"),
        )
        assertEquals("Bare Title", HdqWallsCseSearch.cleanTitle("Bare Title"))
    }

    // -------------------------------------------------------------------- url

    @Test
    fun `resultsUrl shapes the element call like the site's own element`() {
        val config = HdqWallsCseSearch.parseBootstrap(bootstrapJs)!!

        val url = HdqWallsCseSearch.resultsUrl("indian+actress", 10, config)

        assertTrue(url.startsWith("https://cse.google.com/cse/element/v1?"))
        // The site config's result set size and page size.
        assertTrue(url.contains("rsz=filtered_cse"))
        assertTrue(url.contains("num=10"))
        // The element's fixed identifiers.
        assertTrue(url.contains("hl=en"))
        assertTrue(url.contains("source=gcsc"))
        // The engine ID, form-encoded as the element sends it.
        assertTrue(url.contains("cx=partner-pub-9257850376806437%3A3940322700"))
        assertTrue(url.contains("q=indian+actress"))
        assertTrue(url.contains("safe=off"))
        // THE wire-format fix: the token rides as cse_tok — NOT token —
        // form-encoded, and the bootstrap's passengers forward along.
        assertTrue(url.contains("cse_tok=AHbIdTg_d1nKRAMULV0AfMi-g_HA%3A1790741990673"))
        assertTrue(url.contains("cselibv=3735a6ee3000c0cb"))
        assertTrue(url.contains("exp=cc%2Csps%2Cesbfa"))
        assertTrue(url.contains("fexp=121877337%2C122056044%2C121877336%2C122056045"))
        // Page N is result offset (N-1) * PAGE_SIZE; the embedding search
        // page reports itself as rurl.
        assertTrue(url.contains("&start=10&"))
        assertTrue(url.contains("rurl=https%3A%2F%2Fhdqwalls.com%2Fsearch%3Fq%3Dindian%2Bactress"))
        // The JSONP callback the element itself sends rides last.
        assertTrue(url.substringAfterLast('&').matches(Regex("callback=google\\.search\\.cse\\.api\\d+")))
    }

    @Test
    fun `resultsUrl encodes base64-hostile token characters`() {
        val config = HdqWallsCseSearch.parseBootstrap(bootstrapJs)!!.copy(cseToken = "AB+CD/E:F")

        val url = HdqWallsCseSearch.resultsUrl("q", 0, config)

        // A raw `+` would read as a space, a raw `/` as a path separator.
        assertTrue(url.contains("cse_tok=AB%2BCD%2FE%3AF"))
    }
}

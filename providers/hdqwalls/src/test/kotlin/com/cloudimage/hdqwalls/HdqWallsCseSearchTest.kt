package com.cloudimage.hdqwalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google CSE tier's parsing half, over shapes captured from the live
 * flow: the bootstrap config blob's `cse_token` (the key is `cse_token`,
 * NOT `token` — captured from the real `cse.js`), the element API's
 * JSONP-wrapped and bare-JSON answers, the cursor's page offsets, and the
 * result-to-wallpaper mapping readers. The JSONP fixture mirrors what the
 * element answers on the wire (including its `O_o` banner, callback
 * wrapper and `\u0000cc`-style escapes); every defensive edge — Google's
 * rate-limit apology page, empty bodies, foreign hosts — degrades to
 * nulls, never exceptions.
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
    fun `pageSlug maps hdqwalls pages only`() {
        assertEquals(
            "beautiful-indian-actress-wallpaper",
            HdqWallsCseSearch.pageSlug("https://hdqwalls.com/beautiful-indian-actress-wallpaper"),
        )
        assertEquals("gone-wallpaper", HdqWallsCseSearch.pageSlug("https://hdqwalls.com/gone-wallpaper?utm=x"))
        assertNull(HdqWallsCseSearch.pageSlug("https://www.google.com/search?q=cross+link"))
        assertNull(HdqWallsCseSearch.pageSlug("not a url"))
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
        val url = HdqWallsCseSearch.resultsUrl("indian+actress", 10, "AHbIdTg_test:1790741990673")

        assertTrue(url.startsWith("https://cse.google.com/cse/element/v1?"))
        // The engine ID, form-encoded as the element sends it.
        assertTrue(url.contains("cx=partner-pub-9257850376806437%3A3940322700"))
        assertTrue(url.contains("q=indian+actress"))
        // Page N is result offset (N-1) * PAGE_SIZE.
        assertTrue(url.contains("num=10"))
        assertTrue(url.contains("start=10"))
        // The freshly minted bootstrap token rides last.
        assertTrue(url.endsWith("token=AHbIdTg_test:1790741990673"))
        assertTrue(!url.contains("&callback="))
    }
}

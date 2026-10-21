package com.cloudimage.zedge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZedgeParser over the REAL page captures in [ZedgeTestPages] — the
 * live site's own flight streams, trimmed but verbatim. These tests
 * pin the grammar the site's server actually speaks: the listing
 * `initialData` item records, the mixed-content search sections, and
 * the `shareUrl`-bearing detail record — never a DOM guess (the DOM
 * carries no tiles at all; the site hydrates its grids client-side).
 */
class ZedgeParserTest {
    // ------------------------------------------------------------- flight

    @Test
    fun theFlightStreamIsExtractedFromThePushChunks() {
        val flight = ZedgeParser.flightOf(ZedgeTestPages.landing)
        assertTrue("no initialData in stream", flight.contains("\"initialData\""))
        assertTrue("no items in stream", flight.contains("\"items\""))
        assertEquals("a page without chunks has no stream", "", ZedgeParser.flightOf(ZedgeTestPages.plainHtml))
    }

    // ------------------------------------------------------------ listings

    @Test
    fun theLandingStreamYieldsItsTilesInTheSitesOwnOrder() {
        val items = ZedgeParser.parseGrid(ZedgeTestPages.landing)

        assertEquals(6, items.size)
        assertEquals("bf8bf5af-c88f-47b1-8b88-efe39c371c07", items[0].id)
        assertEquals("Winter Escape", items[0].title)
        assertEquals("Cozy Retreat", items[1].title)
        assertEquals("Glowing Bears", items[5].title)
    }

    @Test
    fun everyTileCarriesTheSitesOwnRenditionsVerbatim() {
        val tile = ZedgeParser.parseGrid(ZedgeTestPages.landing).first()

        assertTrue("thumb not the site's CDN: ${tile.thumbUrl}", tile.thumbUrl.startsWith("https://is.zobj.net/image-server/v1/images?r="))
        assertTrue("full not the site's CDN: ${tile.fullUrl}", tile.fullUrl.startsWith("https://is.zobj.net/image-server/v1/images?r="))
        assertNotEquals("grid crop and preview must be distinct renditions", tile.thumbUrl, tile.fullUrl)
    }

    @Test
    fun theCategoryStreamYieldsItsTiles() {
        val items = ZedgeParser.parseGrid(ZedgeTestPages.animeCategory)

        assertEquals(6, items.size)
        assertEquals("7d3918f4-822f-4b50-ab7a-fe19bfc48cb4", items[0].id)
        assertEquals("Anime blue-lock", items[0].title)
    }

    @Test
    fun anEmptyListingParsesToTheHonestZero() {
        assertEquals(0, ZedgeParser.parseGrid(ZedgeTestPages.emptyShelf).size)
    }

    @Test
    fun aPageWithNoStreamParsesToNothing() {
        assertEquals(0, ZedgeParser.parseGrid(ZedgeTestPages.plainHtml).size)
        assertEquals(0, ZedgeParser.parseSearch(ZedgeTestPages.plainHtml).size)
        assertNull(ZedgeParser.parseDetail(ZedgeTestPages.plainHtml))
    }

    @Test
    fun aListingPageServesNoSearchSectionsAndViceVersa() {
        assertEquals(0, ZedgeParser.parseSearch(ZedgeTestPages.landing).size)
        assertEquals(0, ZedgeParser.parseGrid(ZedgeTestPages.search).size)
    }

    // -------------------------------------------------------------- search

    @Test
    fun searchScopesToTheSitesWallpaperSectionAlone() {
        val items = ZedgeParser.parseSearch(ZedgeTestPages.search)

        assertEquals(3, items.size)
        assertEquals("7d3918f4-822f-4b50-ab7a-fe19bfc48cb4", items[0].id)
        assertEquals("Anime blue-lock", items[0].title)
    }

    @Test
    fun anAudioOnlyAnswerParsesToTheHonestZero() {
        // The fixture carries the site's real RINGTONE and NOTIFICATION_SOUND
        // sections — the type scoping must leave none of them standing.
        assertEquals(0, ZedgeParser.parseSearch(ZedgeTestPages.searchAudioOnly).size)
    }

    // -------------------------------------------------------------- detail

    @Test
    fun theDetailRecordReadsEverythingThePageStates() {
        val record = ZedgeParser.parseDetail(ZedgeTestPages.detail)!!

        assertEquals("d5ba41da-102a-4ebc-82a4-08571bb4a0c9", record.id)
        assertEquals("Enchanted Glass Jack-o’-Lanterns", record.title)
        assertEquals("weird shop", record.author)
        assertEquals("https://www.zedge.net/wallpaper/d5ba41da-102a-4ebc-82a4-08571bb4a0c9", record.shareUrl)
        assertEquals(10, record.tags.size)
        assertTrue(record.tags.contains("halloween"))
        assertTrue(record.tags.contains("glass-pumpkin"))
        assertTrue(record.thumbUrl.startsWith("https://is.zobj.net/image-server/v1/images?r="))
        assertTrue(record.fullUrl.startsWith("https://is.zobj.net/image-server/v1/images?r="))
        assertNotEquals(record.thumbUrl, record.fullUrl)
        assertTrue(record.description!!.contains("Glowing glass"))
    }

    @Test
    fun theRelatedItemsShellNeverReadsAsTheWallpaper() {
        // The detail page also carries an empty related-items listing — the
        // shareUrl-bearing record must win, and the shell must never serve
        // as a grid or a detail.
        assertTrue(ZedgeParser.parseGrid(ZedgeTestPages.detail).isEmpty())
        assertTrue(ZedgeParser.parseDetail(ZedgeTestPages.detail) != null)
    }

    @Test
    fun aPageWithoutTheRecordIsUnrecognized() {
        assertNull(ZedgeParser.parseDetail(ZedgeTestPages.landing))
        assertNull(ZedgeParser.parseDetail(ZedgeTestPages.search))
    }
}

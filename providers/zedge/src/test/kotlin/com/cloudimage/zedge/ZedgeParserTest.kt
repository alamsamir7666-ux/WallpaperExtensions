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
    fun theLandingStatesTheCursorThatContinuesIt() {
        val listing = ZedgeParser.parseListing(ZedgeTestPages.landing)

        assertEquals(6, listing.items.size)
        assertEquals(
            // The site's own base64 keyset marker, verbatim — the same
            // cursor its own "Load more" walk rides.
            "X3Njb3JlPTAuMHxmaXJzdFB1Ymxpc2hlZEF0PTE3OTI1NDc3MzIwMDB8aWQ9YzY3YmJiYzgt" +
                "NzgzOS00Njk5LWIyYmUtYjlmMmMxNjA0MGVkfF9wcmVmZXJlbmNlPTUwMDI0YWUxLWFjNzMt" +
                "NGU3Yy04YmY4LTczNDc4NWFiNDVlZQ==",
            listing.nextCursor,
        )
    }

    @Test
    fun everyListingStatesItsOwnContinuation() {
        val landingCursor = ZedgeParser.parseListing(ZedgeTestPages.landing).nextCursor
        val animeCursor = ZedgeParser.parseListing(ZedgeTestPages.animeCategory).nextCursor

        assertTrue(animeCursor!!.startsWith("X3Njb3JlPTUxNC44NDg2fGZpcnN0UHVibGlzaGVkQXQ9MTc5MTY0NDQ2MjAwMH"))
        assertNotEquals("each route hands its own keyset", landingCursor, animeCursor)
    }

    @Test
    fun aCursorHonoringContinuationServesTheNextTiles() {
        val page2 = ZedgeParser.parseListing(ZedgeTestPages.landingPage2)

        assertEquals(3, page2.items.size)
        assertEquals("Aurora Ridge", page2.items[0].title)
        assertEquals("Midnight Bloom", page2.items[1].title)
        assertNotEquals(
            "an advancing page hands the NEXT page's cursor",
            ZedgeParser.parseListing(ZedgeTestPages.landing).nextCursor,
            page2.nextCursor,
        )
    }

    @Test
    fun theWalksLastPageStatesNoContinuation() {
        val page3 = ZedgeParser.parseListing(ZedgeTestPages.landingPage3)

        assertEquals(2, page3.items.size)
        assertEquals("Neon Alley", page3.items[0].title)
        assertNull(page3.nextCursor)
    }

    @Test
    fun theRecycledTilesKeepAFreshLookingCursor() {
        // The guard case must LOOK advancing: the site's own page-1 tiles
        // behind a cursor that is NOT the one the landing handed over.
        val recycled = ZedgeParser.parseListing(ZedgeTestPages.landingRecycled)

        assertEquals(6, recycled.items.size)
        assertEquals("bf8bf5af-c88f-47b1-8b88-efe39c371c07", recycled.items[0].id)
        assertNotEquals(
            ZedgeParser.parseListing(ZedgeTestPages.landing).nextCursor,
            recycled.nextCursor,
        )
    }

    @Test
    fun aPageTheSiteCannotContinueHasNoCursor() {
        val emptyShelfListing = ZedgeParser.parseListing(ZedgeTestPages.emptyShelf)
        assertTrue(emptyShelfListing.items.isEmpty())
        assertNull(emptyShelfListing.nextCursor)

        val plainListing = ZedgeParser.parseListing(ZedgeTestPages.plainHtml)
        assertTrue(plainListing.items.isEmpty())
        assertNull(plainListing.nextCursor)
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
        // Search sections carry no listing cursor anywhere — a search
        // page read as a listing is the honest zero without one.
        assertTrue(ZedgeParser.parseListing(ZedgeTestPages.search).items.isEmpty())
        assertNull(ZedgeParser.parseListing(ZedgeTestPages.search).nextCursor)
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

    @Test
    fun theCapturedMixedSectionsStateNoContinuationCursor() {
        val page = ZedgeParser.parseSearchPage(ZedgeTestPages.search)

        assertEquals(3, page.items.size)
        assertNull("the captured sections carry no `next` marker anywhere", page.nextCursor)
    }

    @Test
    fun aSectionGrammarThatStatesItsOwnCursorHandsItOver() {
        val page = ZedgeParser.parseSearchPage(ZedgeTestPages.searchWallpaperSections)

        assertEquals(3, page.items.size)
        assertEquals(
            "X3Njb3JlPTcuNXxmaXJzdFB1Ymxpc2hlZEF0PTE3OTIzNzUyMDAwMDB8aWQ9NWQ2ZTdmOGEtOWIwYy00ZDFlLThmMmEtM2I0YzVkNmU3ZjhhfF9wcmVmZXJlbmNlPTUwMDI0YWUxLWFjNzMtNGU3Yy04YmY4LTczNDc4NWFiNDVlZQ==",
            page.nextCursor,
        )
    }

    @Test
    fun theWallpapersOnlyRouteSpeaksTheListingGrammarWithItsCursor() {
        val page = ZedgeParser.parseListingOrSearch(ZedgeTestPages.searchWallpapers)

        assertEquals(3, page.items.size)
        assertEquals("Anime blue-lock", page.items[0].title)
        assertEquals(
            "X3Njb3JlPTkuNzV8Zmlyc3RQdWJsaXNoZWRBdD0xNzkyNTQ4MDAwMDAwfGlkPTNmMmE5YzFlLThiNGQtNGU2Zi05YTJiLTdjOGQ1ZTZmN2EwMXxfcHJlZmVyZW5jZT01MDAyNGFlMS1hYzczLTRlN2MtOGJmOC03MzQ3ODVhYjQ1ZWU=",
            page.nextCursor,
        )
    }

    @Test
    fun theDualGrammarReadPrefersTheListingAndFallsBackToTheSections() {
        // A grid page reads as the grid it is — the very listing parseListing serves.
        assertEquals(
            ZedgeParser.parseListing(ZedgeTestPages.landing),
            ZedgeParser.parseListingOrSearch(ZedgeTestPages.landing),
        )
        // A sections page reads its WALLPAPER items the same way parseSearch does.
        assertEquals(
            ZedgeParser.parseSearch(ZedgeTestPages.search),
            ZedgeParser.parseListingOrSearch(ZedgeTestPages.search).items,
        )
        // A page with no grammar at all is the honest zero with no cursor.
        assertTrue(ZedgeParser.parseListingOrSearch(ZedgeTestPages.plainHtml).items.isEmpty())
        assertNull(ZedgeParser.parseListingOrSearch(ZedgeTestPages.plainHtml).nextCursor)
    }

    // ----------------------------------------------------------- graphql

    @Test
    fun theGraphqlAnswerReadsItsItemsAndContinuationCursor() {
        val page = ZedgeParser.parseGraphqlListing(ZedgeTestPages.graphqlContinuation)

        assertEquals(3, page.items.size)
        assertEquals("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", page.items[0].id)
        assertEquals("Ashen Meadow", page.items[0].title)
        assertEquals("Tidal Glass", page.items[2].title)
        assertEquals(
            "X3Njb3JlPTguMTI1fGZpcnN0UHVibGlzaGVkQXQ9MTc5MjU0NzAwMDAwMHxpZD1jM2Q0ZTVmNi1hN2I4" +
                "LTRjOWQtMGUxZi0yYTNiNGM1ZDZlN2Z8X3ByZWZlcmVuY2U9NTAwMjRhZTEtYTdjMy00ZTdjLThi" +
                "ZjgtNzM0Nzg1YWI0NWVl",
            page.nextCursor,
        )
        assertTrue(page.items.all { it.thumbUrl.startsWith("https://is.zobj.net/image-server/v1/images?r=") })
    }

    @Test
    fun theGraphqlAnswerScopesToTheWallpaperTypeAlone() {
        // The fixture carries the site's real RINGTONE record shape — the
        // type scoping must drop it from the continuation lane too.
        val page = ZedgeParser.parseGraphqlListing(ZedgeTestPages.graphqlContinuation)

        assertTrue("a ringtone never serves as a wallpaper", page.items.none { it.id == "d4e5f6a7-b8c9-4d0e-1f2a-3b4c5d6e7f8a" })
        assertEquals(3, page.items.size)
    }

    @Test
    fun theAllDupGraphqlAnswerCarriesTheContextsOwnCursor() {
        // The context-mismatch answer: items byte-identical to the search
        // route's own page-1 tiles, but a `next` marker from the POST's
        // own ranking context — the marker the provider's bridge rides.
        val page = ZedgeParser.parseGraphqlListing(ZedgeTestPages.graphqlAllDupWithContextCursor)
        val ssr = ZedgeParser.parseListing(ZedgeTestPages.searchWallpapers)

        assertEquals(ssr.items.map { it.id }, page.items.map { it.id })
        org.junit.Assert.assertNotEquals(
            "the mismatch answer's cursor is NOT the SSR page's marker",
            ssr.nextCursor,
            page.nextCursor,
        )
        assertEquals(
            "X3Njb3JlPTUuNXxmaXJzdFB1Ymxpc2hlZEF0PTE3OTI1NDcwMDAwMDB8aWQ9ZTU2ZDZmNDgtNjVlMi00" +
                "OTVlLWJjYjctYzk2MjM0NDZhN2FmfF9wcmVmZXJlbmNlPTUwMDI0YWUxLWE3YzMtNGU3Yy04YmY4" +
                "LTczNDc4NWFiNDVlZQ==",
            page.nextCursor,
        )
    }

    @Test
    fun theGraphqlLanesHonestEndStatesNoCursor() {
        val page = ZedgeParser.parseGraphqlListing(ZedgeTestPages.graphqlLastPage)

        assertEquals(2, page.items.size)
        assertEquals("Amber Drift", page.items[0].title)
        assertNull(page.nextCursor)
    }

    @Test
    fun aGraphqlErrorAnswerParsesToTheHonestZero() {
        val error = ZedgeParser.parseGraphqlListing(ZedgeTestPages.graphqlError)
        assertTrue(error.items.isEmpty())
        assertNull(error.nextCursor)

        val unparseable = ZedgeParser.parseGraphqlListing("not json at all")
        assertTrue(unparseable.items.isEmpty())
        assertNull(unparseable.nextCursor)
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

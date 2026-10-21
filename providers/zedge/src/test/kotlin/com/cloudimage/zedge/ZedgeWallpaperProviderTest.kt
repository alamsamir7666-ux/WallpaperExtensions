package com.cloudimage.zedge

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zedge provider over a scripted fake of the plugin-facing HTTP facade.
 *
 * The fixtures are synthesized from the site's own VERIFIED grammar:
 * the `/wallpaper/{uuid}` detail routes exactly as its sitemap system
 * publishes them, the search page's own section headers
 * ("Wallpapers", "Dynamic 24H Wallpapers", "Ringtones",
 * "Notification Sounds", "Artists"), the `h1`/`og:image` detail
 * shapes, and the download-badge chips its tiles carry — because the
 * zone challenges datacenter egress, the live DOM cannot be captured
 * the way the other sources' fixtures were; the ZEDGE_LIVE=1 live
 * checks (and the first real-device session, where the host ladder
 * solves the challenge once visibly) pin the rendered shapes against
 * this grammar. The parser is deliberately class-name-free — it keys
 * on the UUID route and `<img>` tags alone — so the class chrome in
 * these fixtures is representative, not load-bearing.
 *
 * The flat doctrine stays covered without a network: the landing,
 * the two native category routes, the search scoping that never
 * leaks ringtones into wallpapers, the honest empties (audio-only
 * answers, past-the-first pages, unrecognized detail screens), the
 * badge chips that are never titles, and the challenge's 403
 * propagating untouched for the host ladder to own.
 */
class ZedgeWallpaperProviderTest {
    private val provider = ZedgeWallpaperProvider()

    /**
     * URL-routed responses: an exact match wins, then the first
     * prefix match in insertion order; unmatched URLs answer 500 to
     * fail loudly.
     */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            return routes[url]
                ?: routes.entries
                    .filter { (prefix, _) -> url.startsWith(prefix) }
                    .maxByOrNull { (prefix, _) -> prefix.length }
                    ?.value
                ?: ProviderHttpResponse(500, emptyMap(), ByteArray(0))
        }
    }

    private fun ok(body: String): ProviderHttpResponse = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    private fun status(code: Int): ProviderHttpResponse = ProviderHttpResponse(code, emptyMap(), ByteArray(0))

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    // Fixtures: the site's verified grammar with representative chrome.

    /** The Next.js chrome every page carries and the parser must walk past. */
    private val siteChrome =
        """
        <html><head><title>Zedge</title>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <script src="https://www.zedge.net/_next/static/chunk.js"></script>
        <link rel="preload" href="https://www.zedge.net/_next/media/logo.svg">
        </head><body>
        <nav><a href="https://www.zedge.net/wallpapers">Wallpapers</a>
        <a href="https://www.zedge.net/ringtones">Ringtones</a></nav>
        <header><img src="https://www.zedge.net/_next/media/logo.svg" alt="zedge"></header>
        """.trimIndent()

    /** The page-title h1 every listing carries — never a wallpaper's. */
    private val pageTitle = """<h1 class="hero-title">Download HD Phone Wallpapers for Free</h1>"""

    /** A landing tile: image wrapped in the anchor, alt present, badge chip inside. */
    private fun tile(
        uuid: String,
        image: String,
        alt: String? = null,
        badge: String = "10K",
    ): String {
        val altAttr = alt?.let { """ alt="$it"""" } ?: ""
        return """
            <a class="tile" href="https://www.zedge.net/wallpaper/$uuid">
              <img src="$image"$altAttr loading="lazy">
              <span class="badge">$badge</span>
            </a>
            """.trimIndent()
    }

    private val uuidMandala = "00500561-8f3a-42b5-8da6-ad1eb9bb5d01"
    private val uuidWolf = "00501d30-d706-4883-b56b-68c80405ccc0"
    private val uuidPanda = "00502f0f-cc4a-3cdc-95af-75aa06554d9f"

    private val imageMandala = "https://img01.verification-needed/zedge/00500561/preview.jpg"
    private val imageWolf = "https://img01.verification-needed/zedge/00501d30/preview.jpg"
    private val imagePanda = "https://img01.verification-needed/zedge/00502f0f/preview.jpg"

    /** The landing: three tiles with alts, badges in every anchor. */
    private val landingHtml =
        siteChrome + pageTitle + "<main><div class=\"grid\">" +
            tile(uuidMandala, imageMandala, "PopArt Mandala", "120") +
            tile(uuidWolf, imageWolf, "Spirit Wolf", "500") +
            tile(uuidPanda, imagePanda, "Cute Panda", "10") +
            "</div><button type=\"button\">Load more</button></main></body></html>"

    /** A search page: the wallpapers section scoped between headers. */
    private fun searchHtml(wallpaperTiles: String = tile(uuidWolf, imageWolf, "Spirit Wolf")): String =
        siteChrome +
            "<h1>Wolf Ringtones and Wallpapers</h1><main>" +
            "<section><h2>Wallpapers</h2><div class=\"grid\">$wallpaperTiles</div></section>" +
            "<section><h2>Dynamic 24H Wallpapers</h2><div class=\"grid\">" +
            "<a class=\"tile\" href=\"https://www.zedge.net/dynamic-wallpaper/11111111-2222-3333-4444-555555555555\">" +
            "<img src=\"https://img01.verification-needed/zedge/dyn/preview.jpg\" alt=\"Wolf clock\"></a>" +
            "</div></section>" +
            "<section><h2>Ringtones</h2><div class=\"grid\">" +
            "<a class=\"tile\" href=\"https://www.zedge.net/ringtone/22222222-3333-4444-5555-666666666666\">" +
            "<img src=\"https://img01.verification-needed/zedge/ring/preview.jpg\" alt=\"Wolf howl\"></a>" +
            "</div></section>" +
            "</main></body></html>"

    /** A search answer with no wallpapers section at all — audio only. */
    private val ringtonesOnlyHtml =
        siteChrome + "<h1>Sms Tone Ringtones and Wallpapers</h1><main>" +
            "<section><h2>Ringtones</h2><div class=\"grid\">" +
            "<a class=\"tile\" href=\"https://www.zedge.net/ringtone/22222222-3333-4444-5555-666666666666\">" +
            "<img src=\"https://img01.verification-needed/zedge/ring/preview.jpg\" alt=\"Sms tone\"></a>" +
            "</div></section></main></body></html>"

    /** A wallpaper page: h1, hero, og:image and the tag row. */
    private fun detailHtml(
        title: String = "PopArt Mandala",
        hero: String? = "https://img01.verification-needed/zedge/00500561/full.jpg",
        og: String? = "https://img01.verification-needed/zedge/00500561/share.jpg",
    ): String {
        val heroImg = hero?.let { """<img class="hero" src="$it" alt="$title">""" } ?: ""
        val ogMeta = og?.let { """<meta property="og:image" content="$it">""" } ?: ""
        return siteChrome + ogMeta +
            "<main><h1>$title</h1>" + heroImg +
            "<p>120 Downloads</p>" +
            "<div class=\"tags\"><a href=\"https://www.zedge.net/tag/wallpapers/lips/\">lips</a> " +
            "<a href=\"https://www.zedge.net/tag/wallpapers/colourful/\">colourful</a> " +
            "<a href=\"https://www.zedge.net/tag/wallpapers/abstract/\">abstract</a></div>" +
            "</main></body></html>"
    }

    /** The site's own "couldn't find it" screen for a dead UUID. */
    private val deadUuidHtml =
        siteChrome + "<main><h1>Oops, couldn't find it</h1>" +
            "<p>That wallpaper may have been removed.</p></main></body></html>"

    // ------------------------------------------------------------ parser

    @Test
    fun theLandingTilesParseWithIdsAndImages() {
        val tiles = ZedgeParser.parseGrid(landingHtml)

        assertEquals(3, tiles.size)
        assertEquals(uuidMandala, tiles[0].id)
        assertEquals(imageMandala, tiles[0].thumbUrl)
        assertEquals("PopArt Mandala", tiles[0].title)
        assertEquals(uuidPanda, tiles[2].id)
    }

    @Test
    fun tilesTakeTheSiteOwnImageUrlVerbatim() {
        val tiles = ZedgeParser.parseGrid(landingHtml)

        assertTrue(tiles.all { it.thumbUrl.startsWith("https://") })
        assertEquals(listOf(imageMandala, imageWolf, imagePanda), tiles.map { it.thumbUrl })
    }

    @Test
    fun duplicateUuidsDedupeToTheFirstTile() {
        val html =
            landingHtml + tile(uuidMandala, imageMandala, "PopArt Mandala repeated")
        val tiles = ZedgeParser.parseGrid(html)

        assertEquals(3, tiles.size)
        assertEquals("PopArt Mandala", tiles.first { it.id == uuidMandala }.title)
    }

    @Test
    fun aTileWithoutItsOwnImageDrops() {
        val html =
            landingHtml + """<a class="tile" href="/wallpaper/00503040-0223-4cf9-b2eb-a08df20657f7"><span class="badge">500</span></a>"""
        val tiles = ZedgeParser.parseGrid(html)

        assertEquals(3, tiles.size)
    }

    @Test
    fun relativeAndAbsoluteDetailRoutesBothMatch() {
        val html =
            """<a href="/wallpaper/00500561-8f3a-42b5-8da6-ad1eb9bb5d01">""" +
                """<img src="https://img01.verification-needed/a.jpg" alt="A"></a>""" +
                """<a href="https://zedge.net/wallpaper/00501d30-d706-4883-b56b-68c80405ccc0">""" +
                """<img src="https://img01.verification-needed/b.jpg" alt="B"></a>"""
        val tiles = ZedgeParser.parseGrid(html)

        assertEquals(listOf(uuidMandala, uuidWolf), tiles.map { it.id })
    }

    @Test
    fun aUppercaseUuidStillMatches() {
        val html =
            """<a href="/wallpaper/00500561-8F3A-42B5-8DA6-AD1EB9BB5D01"><img src="https://img01.verification-needed/u.jpg" alt="U"></a>"""
        val tiles = ZedgeParser.parseGrid(html)

        assertEquals(1, tiles.size)
        assertEquals("00500561-8F3A-42B5-8DA6-AD1EB9BB5D01", tiles[0].id)
    }

    @Test
    fun anImageAdjacentToTheAnchorStillPairs() {
        val html =
            """
            <div class="tile-wrap">
              <a href="/wallpaper/00500561-8f3a-42b5-8da6-ad1eb9bb5d01"><span class="badge">120</span></a>
              <img src="https://img01.verification-needed/adjacent.jpg" alt="Adjacent era">
            </div>
            <div class="tile-wrap">
              <a href="/wallpaper/00501d30-d706-4883-b56b-68c80405ccc0"><span class="badge">500</span></a>
              <img src="https://img01.verification-needed/adjacent2.jpg" alt="Adjacent era two">
            </div>
            """.trimIndent()
        val tiles = ZedgeParser.parseGrid(html)

        assertEquals(2, tiles.size)
        assertEquals("https://img01.verification-needed/adjacent.jpg", tiles[0].thumbUrl)
        assertEquals("Adjacent era", tiles[0].title)
    }

    @Test
    fun badgeOnlyTilesAnswerANullTitle() {
        val html =
            """<a href="/wallpaper/00500561-8f3a-42b5-8da6-ad1eb9bb5d01"><img src="https://img01.verification-needed/n.jpg"><span>10K</span></a>"""
        val tiles = ZedgeParser.parseGrid(html)

        assertEquals(1, tiles.size)
        assertNull(tiles[0].title)
    }

    @Test
    fun entitiesInAltsDecode() {
        val html =
            """<a href="/wallpaper/00500561-8f3a-42b5-8da6-ad1eb9bb5d01"><img src="https://img01.verification-needed/e.jpg" alt="Wolf &#x27;Howl&#x27; &#8211; Night"></a>"""
        val tiles = ZedgeParser.parseGrid(html)

        assertEquals("Wolf 'Howl' – Night", tiles[0].title)
    }

    @Test
    fun anUnknownListingSpeaksTheHonestZero() {
        val tiles = ZedgeParser.parseGrid("<html><body><p>redesigned</p></body></html>")

        assertTrue(tiles.isEmpty())
    }

    // ------------------------------------------------- search scoping

    @Test
    fun searchScopesToTheWallpapersSectionOnly() {
        val tiles = ZedgeParser.parseSearchGrid(searchHtml())

        assertEquals(1, tiles.size)
        assertEquals(uuidWolf, tiles[0].id)
        assertEquals("Spirit Wolf", tiles[0].title)
    }

    @Test
    fun anAudioOnlyAnswerStaysEmpty() {
        val tiles = ZedgeParser.parseSearchGrid(ringtonesOnlyHtml)

        assertTrue(tiles.isEmpty())
    }

    @Test
    fun aSearchPageWithoutSectionHeadersReadsAsAListing() {
        val tiles = ZedgeParser.parseSearchGrid(landingHtml)

        assertEquals(3, tiles.size)
    }

    @Test
    fun anEmptyWallpapersSectionAnswersZeroTiles() {
        val tiles = ZedgeParser.parseSearchGrid(searchHtml(wallpaperTiles = ""))

        assertTrue(tiles.isEmpty())
    }

    // ------------------------------------------------------------ detail

    @Test
    fun detailReadsTitleHeroAndTags() {
        val record = ZedgeParser.parseDetail(detailHtml())!!

        assertEquals("PopArt Mandala", record.title)
        assertEquals("https://img01.verification-needed/zedge/00500561/full.jpg", record.fullUrl)
        assertEquals("https://img01.verification-needed/zedge/00500561/full.jpg", record.displayThumbUrl)
        assertEquals(listOf("lips", "colourful", "abstract"), record.tags)
    }

    @Test
    fun detailFallsBackToTheOgImageWithoutAHero() {
        val record = ZedgeParser.parseDetail(detailHtml(hero = null))!!

        assertEquals("https://img01.verification-needed/zedge/00500561/share.jpg", record.fullUrl)
    }

    @Test
    fun detailWithoutAnyImageAnswersNoOriginal() {
        val record = ZedgeParser.parseDetail(detailHtml(hero = null, og = null))!!

        assertEquals("PopArt Mandala", record.title)
        assertNull(record.fullUrl)
    }

    @Test
    fun aDeadUuidPageReadsAsImageless() {
        // The site's own "couldn't find it" screen carries an h1 of its
        // own — the parser hands back the imageless record and the
        // provider layer reports it as an unrecognized wallpaper page.
        val record = ZedgeParser.parseDetail(deadUuidHtml)!!
        assertEquals("Oops, couldn't find it", record.title)
        assertNull(record.fullUrl)
    }

    @Test
    fun theChromeLogosNeverBecomeTheHero() {
        val html =
            siteChrome + "<main><h1>Spirit Wolf</h1>" +
                """<img src="https://img01.verification-needed/zedge/logo.svg" alt="logo">""" +
                """<img src="https://img01.verification-needed/zedge/hero.jpg" alt="Spirit Wolf">""" +
                "</main></body></html>"
        val record = ZedgeParser.parseDetail(html)!!

        assertEquals("https://img01.verification-needed/zedge/hero.jpg", record.fullUrl)
    }

    // ----------------------------------------------------------- provider

    @Test
    fun popularServesTheLandingAndTheHonestSinglePage() =
        runTest {
            val client = configureWith(mapOf("https://www.zedge.net/wallpapers" to ok(landingHtml)))

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(3, page.wallpapers.size)
            assertNull(page.nextPage)
            assertEquals(listOf("https://www.zedge.net/wallpapers"), client.requests)
        }

    @Test
    fun theHostVocabularyRidesTheNativeCategoryPages() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.zedge.net/category/wallpapers/anime" to ok(landingHtml),
                        "https://www.zedge.net/category/wallpapers/people" to ok(landingHtml),
                        "https://www.zedge.net/wallpapers" to ok(landingHtml),
                    ),
                )

            val anime =
                provider
                    .popular(
                        page = 1,
                        filters =
                            com.cloudimage.provider.api.Filters
                                .of("category" to "anime"),
                    ).getOrThrow()
            val people =
                provider
                    .popular(
                        page = 1,
                        filters =
                            com.cloudimage.provider.api.Filters
                                .of("category" to "people"),
                    ).getOrThrow()
            val general =
                provider
                    .popular(
                        page = 1,
                        filters =
                            com.cloudimage.provider.api.Filters
                                .of("category" to "general"),
                    ).getOrThrow()

            assertEquals("https://www.zedge.net/category/wallpapers/anime", client.requests[0])
            assertEquals("https://www.zedge.net/category/wallpapers/people", client.requests[1])
            assertEquals("https://www.zedge.net/wallpapers", client.requests[2])
            assertEquals(3, anime.wallpapers.size)
            assertEquals(3, people.wallpapers.size)
            assertEquals(3, general.wallpapers.size)
        }

    @Test
    fun aPagePastTheFirstAnswersTheHonestEmpty() =
        runTest {
            val client = configureWith(mapOf("https://www.zedge.net/wallpapers" to ok(landingHtml)))

            val page = provider.popular(page = 2).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue(client.requests.isEmpty())
        }

    @Test
    fun searchServesTheWallpapersSectionOfTheSiteAnswer() =
        runTest {
            val client = configureWith(mapOf("https://www.zedge.net/find/wolf" to ok(searchHtml())))

            val page = provider.search("wolf", page = 1).getOrThrow()

            assertEquals(1, page.wallpapers.size)
            assertEquals(uuidWolf, page.wallpapers[0].id)
            assertEquals("Spirit Wolf", page.wallpapers[0].title)
            assertNull(page.nextPage)
        }

    @Test
    fun aBlankQueryLandsOnThePopularLanding() =
        runTest {
            val client = configureWith(mapOf("https://www.zedge.net/wallpapers" to ok(landingHtml)))

            val page = provider.search("   ", page = 1).getOrThrow()

            assertEquals(3, page.wallpapers.size)
            assertEquals("https://www.zedge.net/wallpapers", client.requests.single())
        }

    @Test
    fun aMultiWordQueryEncodesHonestly() =
        runTest {
            val client = configureWith(mapOf("https://www.zedge.net/find/wolf+howl" to ok(searchHtml())))

            provider.search("wolf howl", page = 1).getOrThrow()

            assertEquals("https://www.zedge.net/find/wolf+howl", client.requests.single())
        }

    @Test
    fun theChallengeFortyThreePropagatesUntouched() =
        runTest {
            configureWith(mapOf("https://www.zedge.net/wallpapers" to status(403)))

            val result = provider.popular(page = 1)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()!!
            assertTrue("expected ProviderHttpException, got $error", error is com.cloudimage.provider.api.ProviderHttpException)
            assertTrue(error.message!!.contains("403"))
        }

    @Test
    fun anAudioOnlySearchAnswerStaysEmpty() =
        runTest {
            configureWith(mapOf("https://www.zedge.net/find/sms+tone" to ok(ringtonesOnlyHtml)))

            val page = provider.search("sms tone", page = 1).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun suggestionsComeFromTheSiteOwnVocabulary() =
        runTest {
            val suggestions = provider.suggestTags("wolf").getOrThrow()

            assertTrue(suggestions.isNotEmpty())
            assertTrue(suggestions.contains("wolf"))
            assertTrue(suggestions.all { it.contains("wolf", ignoreCase = true) })
            assertTrue(suggestions.size <= 8)
        }

    @Test
    fun aMissPrefixAnswersNoSuggestions() =
        runTest {
            assertTrue(provider.suggestTags("zzzqqq").getOrThrow().isEmpty())
            assertTrue(provider.suggestTags("").getOrThrow().isEmpty())
        }

    @Test
    fun detailTagsJoinTheSuggestionPool() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.zedge.net/wallpaper/$uuidMandala" to ok(detailHtml()),
                    ),
                )

            provider.details(uuidMandala).getOrThrow()

            assertTrue(provider.suggestTags("colour").getOrThrow().isNotEmpty())
            assertTrue(client.requests.single().endsWith("/wallpaper/$uuidMandala"))
        }

    @Test
    fun sectionsServeTheSiteDirectoryAsShelves() =
        runTest {
            val sections = provider.sections()

            assertEquals(14, sections.size)
            assertEquals("popular", sections[0].id)
            assertEquals("Anime", sections[1].title)
            assertEquals(
                com.cloudimage.provider.api.Filters
                    .of("category" to "anime"),
                sections[1].filters,
            )
            assertEquals(
                com.cloudimage.provider.api.Filters
                    .of("query" to "nature"),
                sections[3].filters,
            )
        }

    @Test
    fun detailsBringTheDefinitiveRecord() =
        runTest {
            configureWith(mapOf("https://www.zedge.net/wallpaper/$uuidMandala" to ok(detailHtml())))

            val details = provider.details(uuidMandala).getOrThrow()

            assertEquals(uuidMandala, details.wallpaper.id)
            assertEquals("cloudimage.zedge", details.wallpaper.providerId)
            assertEquals("PopArt Mandala", details.wallpaper.title)
            assertEquals(listOf("lips", "colourful", "abstract"), details.wallpaper.tags)
            assertEquals("https://img01.verification-needed/zedge/00500561/full.jpg", details.wallpaper.fullUrl)
            assertNull(details.resolution)
            assertEquals("https://www.zedge.net/wallpaper/$uuidMandala", details.sourceUrl)
        }

    @Test
    fun aDeadUuidIsASourceFailure() =
        runTest {
            configureWith(mapOf("https://www.zedge.net/wallpaper/$uuidMandala" to ok(deadUuidHtml)))

            val result = provider.details(uuidMandala)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("unrecognized"))
        }

    @Test
    fun aDetailPageWithoutAnyImageIsASourceFailure() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zedge.net/wallpaper/$uuidMandala" to ok(detailHtml(hero = null, og = null)),
                ),
            )

            val result = provider.details(uuidMandala)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("unrecognized"))
        }

    @Test
    fun randomShufflesTheLandingItServes() =
        runTest {
            configureWith(mapOf("https://www.zedge.net/wallpapers" to ok(landingHtml)))

            val batch = provider.random().getOrThrow()

            assertEquals(3, batch.size)
            assertEquals(3, batch.map { it.id }.toSet().size)
        }

    @Test
    fun theMetaDeclaresTheFlatDoctrine() {
        assertEquals("cloudimage.zedge", provider.meta.id)
        assertEquals("Zedge", provider.meta.name)
        assertEquals("1.0.0", provider.meta.versionName)
        assertEquals(
            setOf(Capability.POPULAR, Capability.SEARCH, Capability.TAGS, Capability.FILTERS),
            provider.capabilities,
        )
    }
}

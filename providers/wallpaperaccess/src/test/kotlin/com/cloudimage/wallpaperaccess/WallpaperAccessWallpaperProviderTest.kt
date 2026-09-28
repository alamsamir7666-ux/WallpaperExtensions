package com.cloudimage.wallpaperaccess

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * WallpaperAccess provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the live site's markup: the shared
 * data-attribute listing grid (popular, fresh, collections, slug-guessed
 * search), the single-page site guard, the robots-compliant slug-guess
 * search with its 404-is-a-miss contract, the detail record re-walked
 * from the id's own listing, the host-vocabulary routing, the browser
 * identity every request presents, and the paced-fetch gate's in-flight
 * cap all stay covered without a network.
 */
class WallpaperAccessWallpaperProviderTest {
    private val provider = WallpaperAccessWallpaperProvider()

    /**
     * URL-routed responses; unmatched URLs answer 500 to fail loudly.
     * Routes are checked in insertion order, so listing-specific keys must
     * be registered before shorter prefixes. The headers each request
     * carried are recorded alongside the URLs.
     */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        val headerSets = mutableListOf<Map<String, String>>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            headerSets += headers
            return routes.entries
                .firstOrNull { (prefix, _) -> url.startsWith(prefix) }
                ?.value
                ?: ProviderHttpResponse(500, emptyMap(), ByteArray(0))
        }
    }

    /**
     * A client that parks every request on one gate, so the pace gate's
     * in-flight cap can be observed: while the gate is closed, exactly the
     * capped number of requests ever sit inside the client.
     */
    private class GatedClient : ProviderHttpClient {
        private val gate = CompletableDeferred<Unit>()
        private val active = AtomicInteger(0)
        private val maxObserved = AtomicInteger(0)
        private val servedCount = AtomicInteger(0)

        val maxInFlight: Int get() = maxObserved.get()

        /** Requests that made it through the gate and answered. */
        val served: Int get() = servedCount.get()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            val now = active.incrementAndGet()
            maxObserved.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            gate.await()
            active.decrementAndGet()
            servedCount.incrementAndGet()
            return ProviderHttpResponse(200, emptyMap(), ByteArray(0))
        }

        fun release() {
            gate.complete(Unit)
        }
    }

    private fun ok(body: String): ProviderHttpResponse = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    private fun notFound(): ProviderHttpResponse = ProviderHttpResponse(404, emptyMap(), ByteArray(0))

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    // Fixtures: shapes captured from wallpaperaccess.com, trimmed to the
    // parts the parsers key on.

    /** The popular feed's first cells — real markup, full attribute shape. */
    private val popularGrid =
        """
        <div id="maincontent">
        <div class="flexbox column maincol single_image pad_horisont15">
                        <div id="1353880"
             data-fullimg="/full/1353880.jpg"
             data-or="1920x1200"
             data-fsb="http://www.facebook.com/sharer.php?u=https://wallpaperaccess.com/most-popular#1353880"
             data-tw="https://twitter.com/share?url=https://wallpaperaccess.com/most-popular%231353880&amp;text=Most Popular Wallpaper&amp;hashtags=wallpaper"
             data-download="
             /download/most-popular-1353880"
             class="flexbox_item">
            <div class="wrapper">
                <a href="/download/most-popular-1353880">
                                        <img class=" thumb ads_popup"
                         data-id="1353880"
                         data-slug="most-popular"
                         alt="1920x1200 Most Popular Wallpaper"
                         src="/full/1353880.jpg"
                         fetchpriority="high">
                                        <div class="image_cap">
                        <span class="color_white">1920x1200 Most Popular Wallpaper"></span>
                    </div>
                </a>
            </div>
        </div>
                        <div id="1353883"
             data-fullimg="/full/1353883.jpg"
             data-or="2560x1600"
             data-fsb="http://www.facebook.com/sharer.php?u=https://wallpaperaccess.com/most-popular#1353883"
             data-download="
             /download/most-popular-1353883"
             class="flexbox_item">
            <div class="wrapper">
                <a href="/download/most-popular-1353883">
                                        <img class=" thumb preload ads_popup"
                         data-id="1353883"
                         data-slug="most-popular"
                         alt="2560x1600 Calm Brook 4K Fall Wallpaper. Free 4K Wallpaper"
                         data-src="/full/1353883.jpg">
                </a>
            </div>
        </div>
        </div>
        </div>
        """.trimIndent()

    /** The fall collection's first cell — a png original, lazy img. */
    private val fallGrid =
        """
        <div id="343386"
             data-fullimg="/full/343386.jpg"
             data-or="3840x2160"
             data-pin="http://pinterest.com/pin/create/button/?url=https://wallpaperaccess.com/full/343386.jpg&amp;description=Calm Brook 4K Fall Wallpaper"
             data-download="
             /download/fall-343386"
             class="flexbox_item">
            <div class="wrapper">
                <a href="/download/fall-343386">
                                        <img class=" thumb preload ads_popup"
                         data-id="343386"
                         data-slug="fall"
                         alt="3840x2160 Calm Brook 4K Fall Wallpaper. Free 4K Wallpaper"
                         data-src="/full/343386.jpg">
                </a>
            </div>
        </div>
        """.trimIndent()

    /** The 404 page — a title, and no wallpaper cells at all. */
    private val notFoundPage =
        """
        <!DOCTYPE html>
        <html lang="en">
        <head><title>Page not found - WallpaperAccess</title></head>
        <body><h1>Page not found</h1></body>
        </html>
        """.trimIndent()

    // ------------------------------------------------------------ the feeds

    @Test
    fun `popular serves the ranking's mapped grid items`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/most-popular" to ok(popularGrid),
                    ),
                )

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals("https://wallpaperaccess.com/most-popular", client.requests.single())
            assertEquals(2, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertEquals("most-popular/1353880.jpg", first.id)
            assertEquals("cloudimage.wallpaperaccess", first.providerId)
            assertEquals("https://wallpaperaccess.com/thumb/1353880.jpg", first.thumbUrl)
            assertEquals("https://wallpaperaccess.com/full/1353880.jpg", first.fullUrl)
            assertEquals("Most Popular Wallpaper", first.title)
            // The site's listings publish the TRUE dimensions themselves.
            assertEquals(1920, first.width)
            assertEquals(1200, first.height)
            assertEquals(listOf("most-popular"), first.tags)
            // The site paginates nothing — one batch, no next page.
            assertNull(page.nextPage)
        }

    @Test
    fun `the second cell's lazy img and png-aware extension parse too`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://wallpaperaccess.com/most-popular" to ok(popularGrid),
                ),
            )

            val second = provider.popular(page = 1).getOrThrow().wallpapers[1]

            assertEquals("most-popular/1353883.jpg", second.id)
            assertEquals(2560, second.width)
            assertEquals(1600, second.height)
            assertEquals("Calm Brook 4K Fall Wallpaper. Free 4K Wallpaper", second.title)
            assertEquals("https://wallpaperaccess.com/thumb/1353883.jpg", second.thumbUrl)
        }

    @Test
    fun `page two answers honestly empty without a request`() =
        runTest {
            val client = configureWith(emptyMap())

            val page = provider.popular(page = 2).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue("the site has no page two; no request may leave", client.requests.isEmpty())
        }

    @Test
    fun `host category filters walk the site's own collections`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/anime" to ok(fallGrid),
                        "https://wallpaperaccess.com/people" to ok(fallGrid),
                    ),
                )

            val anime = provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            val people = provider.popular(page = 1, filters = Filters.of("category" to "people")).getOrThrow()

            assertEquals("https://wallpaperaccess.com/anime", client.requests[0])
            assertEquals("https://wallpaperaccess.com/people", client.requests[1])
            // The walked listing's slug rides in the id — the re-fetch address.
            assertEquals("anime/343386.jpg", anime.wallpapers.single().id)
            assertEquals("people/343386.jpg", people.wallpapers.single().id)
        }

    @Test
    fun `sorting date rides the site's fresh feed`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/most-popular" to ok(popularGrid),
                        "https://wallpaperaccess.com/new" to ok(fallGrid),
                    ),
                )

            val page = provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()

            assertEquals("https://wallpaperaccess.com/new", client.requests.single())
            assertEquals("new/343386.jpg", page.wallpapers.single().id)
        }

    // ------------------------------------------------------------- the search

    @Test
    fun `search slug-guesses the site's own collection addresses`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/fall" to ok(fallGrid),
                    ),
                )

            val page = provider.search(query = "Fall", page = 1).getOrThrow()

            assertEquals("https://wallpaperaccess.com/fall", client.requests.single())
            assertEquals("fall/343386.jpg", page.wallpapers.single().id)
            assertEquals(listOf("fall"), page.wallpapers.single().tags)
            assertNull("the site has no pagination", page.nextPage)
        }

    @Test
    fun `a decorated multi-word query slugifies to the collection shape`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/4k-gaming" to ok(fallGrid),
                    ),
                )

            provider.search(query = "  4K GAMING!! ", page = 1).getOrThrow()

            assertEquals("https://wallpaperaccess.com/4k-gaming", client.requests.single())
        }

    @Test
    fun `an unknown collection is a miss, not a failure`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://wallpaperaccess.com/definitely-not-a-collection-xyz" to notFound(),
                ),
            )

            val page = provider.search(query = "definitely not a collection xyz", page = 1).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun `a non-404 search failure stays a source error`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://wallpaperaccess.com/fall" to ProviderHttpResponse(500, emptyMap(), ByteArray(0)),
                ),
            )

            val result = provider.search(query = "fall", page = 1)

            assertTrue(result.isFailure)
            assertEquals("wallpaperaccess answered HTTP 500", result.exceptionOrNull()!!.message)
        }

    @Test
    fun `search page two answers empty instead of repeating the batch`() =
        runTest {
            val client = configureWith(emptyMap())

            val page = provider.search(query = "fall", page = 2).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue("no request may leave for a page the site cannot serve", client.requests.isEmpty())
        }

    @Test
    fun `a blank query lands on the popular first page`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/most-popular" to ok(popularGrid),
                    ),
                )

            val page = provider.search(query = "  ", page = 1).getOrThrow()

            assertEquals("https://wallpaperaccess.com/most-popular", client.requests.single())
            assertTrue(page.wallpapers.isNotEmpty())
        }

    // -------------------------------------------------------------- details

    @Test
    fun `details re-walks the listing the id came from`() =
        runTest {
            val id = "fall/343386.jpg"
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/fall" to ok(fallGrid),
                    ),
                )

            val details = provider.details(id).getOrThrow()

            assertEquals("https://wallpaperaccess.com/fall", client.requests.single())
            assertEquals(id, details.wallpaper.id)
            assertEquals("https://wallpaperaccess.com/full/343386.jpg", details.wallpaper.fullUrl)
            assertEquals("https://wallpaperaccess.com/thumb/343386.jpg", details.wallpaper.thumbUrl)
            assertEquals(3840, details.wallpaper.width)
            assertEquals(2160, details.wallpaper.height)
            assertEquals("3840x2160", details.resolution)
            assertEquals("Calm Brook 4K Fall Wallpaper. Free 4K Wallpaper", details.wallpaper.title)
            assertEquals(listOf("fall"), details.wallpaper.tags)
            // The site's own share address for a wallpaper: its collection,
            // scrolled to the item.
            assertEquals("https://wallpaperaccess.com/fall#343386", details.sourceUrl)
            // The site publishes neither — honest nulls, never inventions.
            assertNull(details.author)
            assertNull(details.fileSizeBytes)
        }

    @Test
    fun `details of an item no longer listed is a source failure`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://wallpaperaccess.com/fall" to ok(popularGrid),
                ),
            )

            val result = provider.details("fall/343386.jpg")

            assertTrue(result.isFailure)
        }

    @Test
    fun `details propagates http failures as source errors`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://wallpaperaccess.com/fall" to ProviderHttpResponse(500, emptyMap(), ByteArray(0)),
                ),
            )

            val result = provider.details("fall/343386.jpg")

            assertTrue(result.isFailure)
            assertEquals("wallpaperaccess answered HTTP 500", result.exceptionOrNull()!!.message)
        }

    // ------------------------------------------------- sections, tags, random

    @Test
    fun `sections ride the host vocabulary across sixteen shelves`() =
        runTest {
            val sections = provider.sections()

            assertEquals(16, sections.size)
            assertEquals(HomeSection.DEFAULT_ID, sections.first { it.id == "popular" }.id)
            assertEquals(setOf("date"), sections.first { it.id == "latest" }.filters.valuesFor("sorting"))
            assertEquals(setOf("anime"), sections.first { it.id == "anime" }.filters.valuesFor("category"))
            assertEquals(setOf("people"), sections.first { it.id == "people" }.filters.valuesFor("category"))
            assertTrue(
                sections
                    .first { it.id == "nature" }
                    .filters
                    .valuesFor("query")
                    .contains("nature"),
            )
            assertTrue(sections.none { it.id == HomeSection.DEFAULT_ID && it.title != "Popular" })
        }

    // --------------------------------------------------- pacing and identity

    @Test
    fun `every request presents the browser user agent`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/most-popular" to ok(popularGrid),
                        "https://wallpaperaccess.com/fall" to ok(fallGrid),
                    ),
                )

            provider.popular(page = 1).getOrThrow()
            provider.search(query = "fall", page = 1).getOrThrow()

            assertEquals(
                "the identity is the traffic the zone is tuned to serve",
                listOf(
                    mapOf(
                        "User-Agent" to
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
                    ),
                    mapOf(
                        "User-Agent" to
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
                    ),
                ),
                client.headerSets,
            )
        }

    @Test
    fun `the pace gate keeps at most three requests in flight`() =
        runTest {
            val client = GatedClient()
            provider.configure(client, ProviderSettings { null })

            // Six concurrent section-shaped calls against a client that
            // parks every request: the gate lets exactly its cap through,
            // the rest wait for permits — never for the parked client.
            val jobs =
                (1..6).map { index ->
                    async { provider.search(query = "collection-$index", page = 1) }
                }
            advanceUntilIdle()

            assertEquals(
                "the gate caps in-flight requests at its limit",
                3,
                client.maxInFlight,
            )

            client.release()
            jobs.awaitAll()

            assertEquals("every queued fetch ran", 6, client.served)
            assertTrue("every call settled", jobs.all { it.isCompleted })
        }

    @Test
    fun `suggestTags answers from collection names already seen, never from requests`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/fall" to ok(fallGrid),
                    ),
                )
            provider.search(query = "fall", page = 1).getOrThrow()

            assertTrue(provider.suggestTags("fal").getOrThrow().contains("fall"))
            assertTrue(provider.suggestTags("FALL").getOrThrow().contains("fall"))
            assertTrue(provider.suggestTags("zzz").getOrThrow().isEmpty())
            assertTrue(provider.suggestTags("  ").getOrThrow().isEmpty())
            assertEquals(1, client.requests.size)
        }

    @Test
    fun `random serves the freshest batch the site itself labels new`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wallpaperaccess.com/new" to ok(fallGrid),
                    ),
                )

            val wallpapers = provider.random().getOrThrow()

            assertEquals("https://wallpaperaccess.com/new", client.requests.single())
            assertEquals(1, wallpapers.size)
        }

    // ------------------------------------------------------- parser details

    @Test
    fun `a malformed cell drops without swallowing its neighbor`() {
        val grid =
            """
            <div id="111" class="flexbox_item"><div class="wrapper"><span>broken</span></div></div>
            <div id="343386"
                 data-fullimg="/full/343386.jpg"
                 data-or="3840x2160"
                 class="flexbox_item">
                <div class="wrapper"><a href="/download/fall-343386">
                    <img class="thumb" data-id="343386" data-slug="fall" alt="3840x2160 Calm Brook" data-src="/full/343386.jpg">
                </a></div>
            </div>
            """.trimIndent()

        val items = WallpaperAccessParser.parseGrid(grid)

        assertEquals(1, items.size)
        assertEquals("343386", items.single().numericId)
    }

    @Test
    fun `a cell without data-or keeps its dimensions honestly null`() {
        val grid =
            """
            <div id="343386"
                 data-fullimg="/full/343386.jpg"
                 class="flexbox_item">
                <div class="wrapper"><a href="/download/fall-343386">
                    <img class="thumb" data-id="343386" data-slug="fall" alt="Calm Brook" data-src="/full/343386.jpg">
                </a></div>
            </div>
            """.trimIndent()

        val item = WallpaperAccessParser.parseGrid(grid).single()

        assertNull(item.width)
        assertNull(item.height)
        // The alt carried no dims prefix — the title is the whole alt.
        assertEquals("Calm Brook", item.title)
    }

    @Test
    fun `attribute order is never assumed`() {
        val grid =
            """
            <div class="flexbox_item" data-or="1000x500" data-fullimg="/full/999.png" id="999">
                <div class="wrapper"><a href="/download/odd-999">
                    <img data-slug="odd" data-id="999" alt="999x500 Shuffled Attributes" class="thumb">
                </a></div>
            </div>
            """.trimIndent()

        val item = WallpaperAccessParser.parseGrid(grid).single()

        assertEquals("999", item.numericId)
        assertEquals("999.png", item.fileName)
        assertEquals(1000, item.width)
        assertEquals(500, item.height)
        assertEquals("Shuffled Attributes", item.title)
        assertEquals("odd", item.slug)
    }

    @Test
    fun `the 404 page parses to zero items`() {
        assertTrue(WallpaperAccessParser.parseGrid(notFoundPage).isEmpty())
    }

    @Test
    fun `homepage collection cards carry no wallpaper cells`() {
        val homeCards =
            """
            <div class="ui center aligned stackable three column grid container">
                            <div class="column collection_thumb">
                <a title="Fall Wallpapers" class="ui fluid image" href="/fall">
                <img class="preload" alt="Fall Wallpaper" data-src="/thumb/343686.jpg" src="/thumb/343686.jpg">
                </a>
                            </div>
            </div>
            """.trimIndent()

        assertTrue(WallpaperAccessParser.parseGrid(homeCards).isEmpty())
    }

    @Test
    fun `entity-escaped titles unescape before the dims strip`() {
        val grid =
            """
            <div id="555" data-fullimg="/full/555.jpg" data-or="800x600" class="flexbox_item">
                <div class="wrapper"><a href="/download/odd-555">
                    <img data-id="555" data-slug="odd" alt="800x600 Nature &amp; &#65;nimals &#x27;scene&#x27;">
                </a></div>
            </div>
            """.trimIndent()

        val item = WallpaperAccessParser.parseGrid(grid).single()

        assertEquals("Nature & Animals 'scene'", item.title)
    }

    @Test
    fun `duplicate cells dedupe by numeric id as insurance`() {
        val grid =
            """
            <div id="343386" data-fullimg="/full/343386.jpg" data-or="3840x2160" class="flexbox_item">
                <div class="wrapper"><a href="/download/fall-343386"><img data-id="343386" data-slug="fall" alt="3840x2160 Calm Brook"></a></div>
            </div>
            <div id="343386" data-fullimg="/full/343386.jpg" data-or="3840x2160" class="flexbox_item">
                <div class="wrapper"><a href="/download/fall-343386"><img data-id="343386" data-slug="fall" alt="3840x2160 Calm Brook"></a></div>
            </div>
            """.trimIndent()

        assertEquals(1, WallpaperAccessParser.parseGrid(grid).size)
    }

    @Test
    fun `slugify speaks the site's address shape`() {
        assertEquals("fall", WallpaperAccessParser.slugify("  Fall  "))
        assertEquals("4k-gaming", WallpaperAccessParser.slugify("4K GAMING!!"))
        assertEquals("naruto", WallpaperAccessParser.slugify("Naruto"))
        assertEquals("", WallpaperAccessParser.slugify("!!! ..."))
    }
}

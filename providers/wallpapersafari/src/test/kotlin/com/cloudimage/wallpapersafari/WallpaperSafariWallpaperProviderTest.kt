package com.cloudimage.wallpapersafari

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WallpaperSafari provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the live site's markup: the home wall's
 * two grid shapes (desktop and phone cards), the topic galleries'
 * post-image blocks with their sibling like widgets, the real search
 * endpoint's gallery cards with the hit/miss headings that separate
 * results from trending suggestions, the two-step search merge with its
 * batching and dedupe, the category-directory streams, the /w/{id}
 * detail record with its true dimensions, and the host-vocabulary
 * routing all stay covered without a network.
 */
class WallpaperSafariWallpaperProviderTest {
    private val provider = WallpaperSafariWallpaperProvider()

    /**
     * URL-routed responses; unmatched URLs answer 500 to fail loudly.
     * Routes are checked in insertion order.
     */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            return routes.entries
                .firstOrNull { (prefix, _) -> url.startsWith(prefix) }
                ?.value
                ?: ProviderHttpResponse(500, emptyMap(), ByteArray(0))
        }
    }

    private fun ok(body: String): ProviderHttpResponse = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    private fun notFound(): ProviderHttpResponse = ProviderHttpResponse(404, emptyMap(), ByteArray(0))

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    // Fixtures: shapes captured from wallpapersafari.com, trimmed to the
    // parts the parsers key on.

    /** The home wall's first cells — real markup: a desktop card and a phone card. */
    private val homeGrid =
        """
        <a href="/w/TvuM20" class="gallery-item desktop-image download-modal" data-img="https://mcdn.wallpapersafari.com/medium/79/73/TvuM20.jpg" data-id="TvuM20" data-username="michaelhanson" data-firstname="Michael" data-pageslug="">
                        <img src="https://mcdn.wallpapersafari.com/medium/79/73/TvuM20.jpg" alt="2560x1440 wallpaper" width="340" height="170" style="width: 100%; height: 100%;object-fit:cover;aspect-ratio: 7/4;" class="gallery-image" loading="lazy">
            <span class="like" data-likes="914" data-id="TvuM20" data-path="79/73/TvuM20.jpg" data-gallery-slug="w/TvuM20" data-width="2560" data-height="1440"><span class="ico icon-heart-empty"></span><span class="like-counter">914</span></span>
            <span class="image_funcs">
                <span class="view-gallery-link">2560x1440</span>
            </span>
          </a>
        <a href="/w/2ABOTb" class="gallery-item phone-image download-modal" data-img="https://mcdn.wallpapersafari.com/medium/97/1/2ABOTb.jpg" data-id="2ABOTb" data-username="ryany80" data-firstname="Ryan" data-pageslug="">
                        <img src="https://mcdn.wallpapersafari.com/medium/97/1/2ABOTb.jpg" alt="1080x1920 wallpaper" width="340" height="680" style="aspect-ratio: 4/7;" class="gallery-image" loading="lazy">
            <span class="like" data-likes="489" data-id="2ABOTb" data-path="97/1/2ABOTb.jpg" data-gallery-slug="w/2ABOTb" data-width="1080" data-height="1920"><span class="ico icon-heart-empty"></span><span class="like-counter">489</span></span>
          </a>
        """.trimIndent()

    /**
     * A home cell with attributes reordered (class before href) and single
     * quotes — the site mixes both styles; the parser must read either.
     */
    private val homeGridReordered =
        """
        <a class='gallery-item desktop-image download-modal' data-img='https://mcdn.wallpapersafari.com/medium/11/5/zZ9xW2.jpg' data-id='zZ9xW2' href='/w/zZ9xW2' data-username='tnguyen'>
            <img src="https://mcdn.wallpapersafari.com/medium/11/5/zZ9xW2.jpg" alt="Sunrise Over The Mountains" width="340" height="191" class="gallery-image">
            <span class="like" data-likes="12" data-id="zZ9xW2" data-path="11/5/zZ9xW2.jpg" data-width="3840" data-height="2160"></span>
          </a>
        """.trimIndent()

    /** The home wall's debris — an anchor without a like widget; it drops, its neighbors survive. */
    private val homeGridWithDebris =
        """
        <a href="/w/broken1" class="gallery-item desktop-image" data-img="https://mcdn.wallpapersafari.com/medium/1/2/broken1.jpg">
            <img src="https://mcdn.wallpapersafari.com/medium/1/2/broken1.jpg" alt="1920x1080 wallpaper" class="gallery-image">
          </a>
        """.trimIndent() + homeGrid

    /** A topic gallery's first blocks — real markup from indian-actress-wallpapers. */
    private val topicGrid =
        """
        <div  id="QHch2q" class="post-image QHch2q landscape " data-id="QHch2q">
            <div class="wrap-image clearfix">
                <a href="/w/QHch2q" class="imgshadow bg-landscape" data-img="https://mcdn.wallpapersafari.com/medium/48/30/QHch2q.jpg" data-id="QHch2q" data-username="lvasquez7" data-firstname="Lisa" data-pageslug="indian-actress-wallpapers">
                        <picture>
                                                <source media="(min-width: 760px)" srcset="https://mcdn.wallpapersafari.com/medium/48/30/QHch2q.jpg, https://cdn.wallpapersafari.com/48/30/QHch2q.jpg 2x">
                <source media="(min-width: 335px)" srcset="https://mcdn.wallpapersafari.com/335/48/30/QHch2q.jpg, https://mcdn.wallpapersafari.com/medium/48/30/QHch2q.jpg 2x">
                                                <img src="https://cdn.wallpapersafari.com/48/30/QHch2q.jpg" width="1024" height="768" alt="HD Wallpaper Bollywood Actress" class="img-fluid galimg img-landscape" loading="eager">
            </picture>
        </a>
    </div>
            <div class="imginfo">
        <a class="download_button" href="https://wallpapersafari.com/w/QHch2q">View</a>
                  <span class="fouricons like" data-likes="839" data-id="QHch2q" data-path="48/30/QHch2q.jpg" data-gallery-slug="indian-actress-wallpapers" data-width="1024" data-height="768"><span class="ico icon-heart"></span><span class="like-counter">839</span></span>
                        <div class="flright">
                                    <span class="image-info"><span class="ico icon-picture">1024x768</span></span>
            <a href="/user/lvasquez7/" class="image-info"><span class="author-link">lvasquez7</span></a>
        </div>
    </div>
</div>
        <div  id="ef8Gc1" class="post-image ef8Gc1 landscape " data-id="ef8Gc1">
    <div class="wrap-image clearfix">
        <a href="/w/ef8Gc1" class="imgshadow bg-landscape" data-img="https://mcdn.wallpapersafari.com/medium/99/45/ef8Gc1.jpg" data-id="ef8Gc1" data-username="portiz33" data-firstname="Penny" data-pageslug="indian-actress-wallpapers">
                        <picture>
                                                <img src="https://cdn.wallpapersafari.com/99/45/ef8Gc1.jpg" width="1920" height="1080" alt="South Indian Actress 4k" class="img-fluid galimg img-landscape" loading="eager">
            </picture>
        </a>
    </div>
            <div class="imginfo">
        <span class="fouricons like" data-likes="601" data-id="ef8Gc1" data-path="99/45/ef8Gc1.jpg" data-gallery-slug="indian-actress-wallpapers" data-width="1920" data-height="1080"><span class="like-counter">601</span></span>
    </div>
</div>
        """.trimIndent()

    /** A PNG item — the CDN keeps the true extension; the parser must not rewrite it. */
    private val topicGridPng =
        """
        <div  id="l20DZi" class="post-image l20DZi portrait " data-id="l20DZi">
            <div class="wrap-image clearfix">
                <a href="/w/l20DZi" class="imgshadow bg-portrait" data-img="https://mcdn.wallpapersafari.com/medium/35/18/l20DZi.png" data-id="l20DZi" data-username="jdiaz88" data-pageslug="labubu-wallpapers">
                    <picture>
                        <img src="https://cdn.wallpapersafari.com/35/18/l20DZi.png" width="400" height="225" alt="Labubu Wallpaper For Desktop" class="img-fluid galimg img-portrait" loading="eager">
                    </picture>
                </a>
            </div>
        </div>
        """.trimIndent()

    /** The search page's own hit heading — everything above the cards. */
    private val searchHitHeading =
        """
        <div class="relimgs">
            <div class="title">
                    <h2 class="subh1">You searched for:</h2>
                    <h1 class="h2">Indian Actress <span class="color_grey"> wallpaper galleries we found 🕵️‍♂️</h1>
                    <p class="subtitle">Browse through some of the most popular galleries</p>
            </div>
        """.trimIndent()

    /** The search page's own miss heading, above a wall of trending cards. */
    private val searchMissHeading =
        """
        <div class="relimgs trending">
                  <div class="title">
                    <h2 class="subh1">You searched for:</h2>
                    <h2 class="h2">Oooops! 👀 We couldn't find anything 🙈</h2>
                    <p class="subtitle">Check out our trending galleries or refine your search</p>
          </div>
        """.trimIndent()

    /** The indian-actress search result — one gallery card, real markup. */
    private val indianActressCard =
        """
        <a href="/indian-actress-wallpapers/" class="rel_a" title="Indian Actress Wallpapers">
                                                <picture>
                            <img src="https://wallpapersafari.com/image/indian-actress-wallpapers.jpg" width="360" height="220" alt="Indian Actress" class="img-fluid rel_img" loading="lazy">
                        </picture>
                                                <span class="rblack">
                                  <span class="rtitle">Indian Actress Wallpapers</span>
                                  <span class="ramount">89 images</span>
                                </span>
                                        </a>
        """.trimIndent()

    /** Four gallery cards — enough to exercise the three-per-page batching. */
    private val fourCards =
        listOf(
            "blue-anime-aesthetic-wallpapers" to "Blue Anime Aesthetic Wallpapers",
            "my-hero-academia-anime-wallpapers" to "My Hero Academia Anime Wallpapers",
            "anime-cartoon-wallpapers" to "Anime Cartoon Wallpapers",
            "green-anime-wallpapers" to "Green Anime Wallpapers",
        ).joinToString("\n") { (slug, title) ->
            """
            <a href="/$slug/" class="rel_a" title="$title">
                <picture><img src="https://wallpapersafari.com/image/$slug.jpg" class="img-fluid rel_img"></picture>
                <span class="rblack"><span class="rtitle">$title</span> <span class="ramount">44 images</span></span>
            </a>
            """.trimIndent()
        }

    /** The detail page's definitive record — real markup from /w/TvuM20. */
    private val detailPage =
        """
        <h1 class="title_h1">Facets Megatron Dark Desktop Wallpaper</h1>
        <a href="/w/TvuM20" class="imgshadow_single" data-img="https://mcdn.wallpapersafari.com/medium/79/73/TvuM20.jpg" data-id="TvuM20" data-username="michaelhanson" data-firstname="Michael Hanson" data-pageslug="4k-dark-wallpaper">
            <picture>
                <img fetchpriority="high" src="https://cdn.wallpapersafari.com/79/73/TvuM20.jpg" width="2560" height="1440" alt="Facets Megatron Dark Desktop Wallpaper" class="img-fluid img-landscape" loading="eager">
            </picture>
        </a>
        <meta property="og:image" content="https://cdn.wallpapersafari.com/79/73/TvuM20.jpg" />
        """.trimIndent()

    // ----------------------------------------------------------- home wall

    @Test
    fun `the home wall parses with true dimensions and disclosed originals`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/" to ok(homeGrid)))

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertNull("the home wall is one honest page", page.nextPage)
            val first = page.wallpapers[0]
            assertEquals("TvuM20", first.id)
            assertEquals("cloudimage.wallpapersafari", first.providerId)
            assertEquals("https://mcdn.wallpapersafari.com/medium/79/73/TvuM20.jpg", first.thumbUrl)
            assertEquals("https://cdn.wallpapersafari.com/79/73/TvuM20.jpg", first.fullUrl)
            assertEquals(2560, first.width)
            assertEquals(1440, first.height)
            assertNull("the home wall's alt is a resolution label, not a title", first.title)
        }

    @Test
    fun `the home wall's generic alt yields no title and no topic tag`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/" to ok(homeGrid)))

            val page = provider.popular(page = 1).getOrThrow()

            // `2560x1440 wallpaper` is a resolution label, not a title.
            assertNull(page.wallpapers[0].title)
            assertTrue("an item with neither topic nor real title carries no tags", page.wallpapers[0].tags.isEmpty())
        }

    @Test
    fun `a reordered single-quoted home cell still parses`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/" to ok(homeGridReordered)))

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(1, page.wallpapers.size)
            val item = page.wallpapers[0]
            assertEquals("zZ9xW2", item.id)
            assertEquals("Sunrise Over The Mountains", item.title)
            assertEquals(3840, item.width)
            assertEquals(2160, item.height)
            assertEquals("https://cdn.wallpapersafari.com/11/5/zZ9xW2.jpg", item.fullUrl)
        }

    @Test
    fun `a home cell without a like widget drops and its neighbors survive`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/" to ok(homeGridWithDebris)))

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals("the debris drops, both good cells survive", 2, page.wallpapers.size)
            assertEquals(listOf("TvuM20", "2ABOTb"), page.wallpapers.map { it.id })
        }

    @Test
    fun `past page one the home wall is honestly empty`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/" to ok(homeGrid)))

            val page = provider.popular(page = 2).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    // -------------------------------------------------------- topic grids

    @Test
    fun `a topic gallery parses with its own original URLs and authors`() {
        val items = WallpaperSafariParser.parseTopicGrid(topicGrid)

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("QHch2q", first.id)
        assertEquals("https://mcdn.wallpapersafari.com/medium/48/30/QHch2q.jpg", first.thumbUrl)
        assertEquals("https://cdn.wallpapersafari.com/48/30/QHch2q.jpg", first.originalUrl)
        assertEquals(1024, first.width)
        assertEquals(768, first.height)
        assertEquals("HD Wallpaper Bollywood Actress", first.title)
        assertEquals("lvasquez7", first.author)
        assertEquals("indian-actress-wallpapers", first.topicSlug)
        assertEquals("ef8Gc1", items[1].id)
        assertEquals(1080, items[1].height)
    }

    @Test
    fun `a PNG original keeps its extension`() {
        val items = WallpaperSafariParser.parseTopicGrid(topicGridPng)

        assertEquals(1, items.size)
        assertEquals("https://cdn.wallpapersafari.com/35/18/l20DZi.png", items[0].originalUrl)
        assertEquals("https://mcdn.wallpapersafari.com/medium/35/18/l20DZi.png", items[0].thumbUrl)
        assertEquals("labubu-wallpapers", items[0].topicSlug)
    }

    @Test
    fun `URL transforms round-trip between the two CDNs`() {
        assertEquals(
            "https://cdn.wallpapersafari.com/79/73/TvuM20.jpg",
            WallpaperSafariParser.toOriginalUrl("https://mcdn.wallpapersafari.com/medium/79/73/TvuM20.jpg"),
        )
        assertEquals(
            "https://mcdn.wallpapersafari.com/medium/79/73/TvuM20.jpg",
            WallpaperSafariParser.toThumbUrl("https://cdn.wallpapersafari.com/79/73/TvuM20.jpg"),
        )
        assertNull(WallpaperSafariParser.toOriginalUrl("https://example.com/medium/79/73/TvuM20.jpg"))
        assertNull(WallpaperSafariParser.toThumbUrl("https://example.com/79/73/TvuM20.jpg"))
    }

    // -------------------------------------------------------------- search

    @Test
    fun `search matching one gallery serves it whole`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapersafari.com/search?q=indian+actress" to ok(searchHitHeading + indianActressCard),
                    "https://wallpapersafari.com/indian-actress-wallpapers/" to ok(topicGrid),
                ),
            )

            val page = provider.search(query = "indian actress", page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals("QHch2q", page.wallpapers[0].id)
            assertEquals("HD Wallpaper Bollywood Actress", page.wallpapers[0].title)
            assertEquals("indian actress", page.wallpapers[0].tags.first())
            assertNull("no further galleries remain", page.nextPage)
        }

    @Test
    fun `search batches three galleries per page and walks on`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapersafari.com/search?q=anime" to ok(searchHitHeading + fourCards),
                    "https://wallpapersafari.com/blue-anime-aesthetic-wallpapers/" to ok(topicGrid),
                    "https://wallpapersafari.com/my-hero-academia-anime-wallpapers/" to ok(topicGridPng),
                    "https://wallpapersafari.com/anime-cartoon-wallpapers/" to ok(topicGrid),
                    "https://wallpapersafari.com/green-anime-wallpapers/" to ok(topicGridPng),
                ),
            )

            val page1 = provider.search(query = "anime", page = 1).getOrThrow()

            assertEquals(3, page1.wallpapers.size)
            assertEquals(listOf("QHch2q", "ef8Gc1", "l20DZi"), page1.wallpapers.map { it.id })
            assertEquals(2, page1.nextPage)

            val page2 = provider.search(query = "anime", page = 2).getOrThrow()

            assertEquals("the fourth gallery rides page two alone", listOf("l20DZi"), page2.wallpapers.map { it.id })
            assertNull(page2.nextPage)
        }

    @Test
    fun `a failing gallery is skipped without sinking the page`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapersafari.com/search?q=anime" to ok(searchHitHeading + fourCards),
                    "https://wallpapersafari.com/blue-anime-aesthetic-wallpapers/" to ok(topicGrid),
                    "https://wallpapersafari.com/my-hero-academia-anime-wallpapers/" to notFound(),
                    "https://wallpapersafari.com/anime-cartoon-wallpapers/" to ok(topicGridPng),
                ),
            )

            val page = provider.search(query = "anime", page = 1).getOrThrow()

            assertEquals(
                "the 404 gallery's wallpapers vanish, its neighbors survive",
                listOf(
                    "QHch2q",
                    "ef8Gc1",
                    "l20DZi",
                ),
                page.wallpapers.map {
                    it.id
                },
            )
        }

    @Test
    fun `the zero-result marker yields an honest empty page and never serves trending cards`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapersafari.com/search?q=zzxxqwerty" to
                            ok(searchMissHeading + fourCards),
                    ),
                )

            val page = provider.search(query = "zzxxqwerty", page = 1).getOrThrow()

            assertTrue("trending suggestions are not results", page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertEquals("no gallery page is ever fetched for a miss", 1, client.requests.size)
        }

    @Test
    fun `past the deep cap search is honestly empty`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapersafari.com/search?q=anime" to ok(searchHitHeading + fourCards),
                    "https://wallpapersafari.com/blue-anime-aesthetic-wallpapers/" to ok(topicGrid),
                ),
            )

            val page = provider.search(query = "anime", page = 11).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun `a blank query lands on the home wall`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/" to ok(homeGrid)))

            val page = provider.search(query = "  ", page = 1).getOrThrow()

            assertEquals(listOf("TvuM20", "2ABOTb"), page.wallpapers.map { it.id })
        }

    @Test
    fun `search does not re-issue the query per pagination page`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapersafari.com/search?q=anime" to ok(searchHitHeading + fourCards),
                        "https://wallpapersafari.com/blue-anime-aesthetic-wallpapers/" to ok(topicGrid),
                        "https://wallpapersafari.com/my-hero-academia-anime-wallpapers/" to ok(topicGrid),
                        "https://wallpapersafari.com/anime-cartoon-wallpapers/" to ok(topicGrid),
                        "https://wallpapersafari.com/green-anime-wallpapers/" to ok(topicGrid),
                    ),
                )

            provider.search(query = "anime", page = 1).getOrThrow()
            provider.search(query = "anime", page = 2).getOrThrow()

            val searchRequests = client.requests.count { it.startsWith("https://wallpapersafari.com/search?") }
            assertEquals("the gallery-card cache carries the scroll: one query request for both pages", 1, searchRequests)
        }

    // ----------------------------------------------------------- categories

    @Test
    fun `the anime category walks the site's anime directory`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapersafari.com/category/art/anime/" to ok(searchHitHeading + fourCards),
                    "https://wallpapersafari.com/blue-anime-aesthetic-wallpapers/" to ok(topicGrid),
                    "https://wallpapersafari.com/my-hero-academia-anime-wallpapers/" to ok(topicGridPng),
                    "https://wallpapersafari.com/anime-cartoon-wallpapers/" to ok(topicGrid),
                ),
            )

            val page = provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals(3, page.wallpapers.size)
            assertEquals(2, page.nextPage)
        }

    @Test
    fun `category pages dedupe ids an earlier page already served`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapersafari.com/category/art/anime/" to ok(searchHitHeading + fourCards),
                    "https://wallpapersafari.com/blue-anime-aesthetic-wallpapers/" to ok(topicGrid),
                    "https://wallpapersafari.com/my-hero-academia-anime-wallpapers/" to ok(topicGrid),
                    "https://wallpapersafari.com/anime-cartoon-wallpapers/" to ok(topicGrid),
                    "https://wallpapersafari.com/green-anime-wallpapers/" to ok(topicGridPng),
                ),
            )

            val page1 = provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            val page2 = provider.popular(page = 2, filters = Filters.of("category" to "anime")).getOrThrow()

            // Pages one and two share no gallery, but the same ids appear
            // in both galleries' fixtures; the served-id window absorbs them.
            assertTrue(page2.wallpapers.none { it.id in page1.wallpapers.map { w -> w.id } })
        }

    @Test
    fun `an unmapped category value falls back to the home wall`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/" to ok(homeGrid)))

            val page = provider.popular(page = 1, filters = Filters.of("category" to "people")).getOrThrow()

            assertEquals(
                "the site files no people category; the default wall is the honest answer",
                listOf(
                    "TvuM20",
                    "2ABOTb",
                ),
                page.wallpapers.map {
                    it.id
                },
            )
        }

    // ------------------------------------------------------------- details

    @Test
    fun `details reads the definitive record`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/w/TvuM20" to ok(detailPage)))

            val details = provider.details("TvuM20").getOrThrow()

            assertEquals("TvuM20", details.wallpaper.id)
            assertEquals("Facets Megatron Dark Desktop Wallpaper", details.wallpaper.title)
            assertEquals("https://cdn.wallpapersafari.com/79/73/TvuM20.jpg", details.wallpaper.fullUrl)
            assertEquals("https://mcdn.wallpapersafari.com/medium/79/73/TvuM20.jpg", details.wallpaper.thumbUrl)
            assertEquals(2560, details.wallpaper.width)
            assertEquals(1440, details.wallpaper.height)
            assertEquals("michaelhanson", details.author)
            assertEquals("2560x1440", details.resolution)
            assertEquals("https://wallpapersafari.com/w/TvuM20", details.sourceUrl)
            assertTrue("the gallery slug rides along as a tag", "4k dark" in details.wallpaper.tags)
        }

    @Test
    fun `a missing wallpaper is a source failure, not a guess`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/w/gone123" to notFound()))

            val result = provider.details("gone123")

            assertTrue("the id alone discloses no image URL — nothing to degrade to", result.isFailure)
        }

    // ------------------------------------------------------ suggestions

    @Test
    fun `suggestTags answers from galleries this instance has seen`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapersafari.com/search?q=indian+actress" to ok(searchHitHeading + indianActressCard),
                    "https://wallpapersafari.com/indian-actress-wallpapers/" to ok(topicGrid),
                ),
            )
            provider.search(query = "indian actress", page = 1).getOrThrow()

            val suggestions = provider.suggestTags("actress").getOrThrow()

            assertTrue("indian actress" in suggestions)
            assertTrue(provider.suggestTags("zzznothing").getOrThrow().isEmpty())
        }

    @Test
    fun `a fresh instance answers no suggestions`() =
        runTest {
            configureWith(mapOf("https://wallpapersafari.com/" to ok(homeGrid)))

            assertTrue(provider.suggestTags("anything").getOrThrow().isEmpty())
        }

    // ------------------------------------------------------------ sections

    @Test
    fun `sections offer the home wall, the anime directory and query presets`() =
        runTest {
            val sections = provider.sections()

            assertEquals(11, sections.size)
            assertEquals("popular", sections.first().id)
            assertTrue(sections.any { it.id == "anime" && it.filters.isSelected("category", "anime") })
            assertTrue(sections.any { it.id == "girls" && it.filters.isSelected("query", "girls") })
        }

    @Test
    fun `no section speaks a category value the host vocabulary cannot translate`() =
        runTest {
            // The host's section-to-query mapping recognizes only
            // general/anime/people under `category`; any other value is
            // dropped, the section's query collapses to the blank
            // default, and the tab shows the popular wall — exactly the
            // 1.0.0 bug where Art, Animals, Cars, Nature, Sports and
            // Travel all showed the same wallpapers. Custom shelves must
            // ride the `query` key instead.
            val speakable = setOf("general", "anime", "people")

            provider.sections().forEach { section ->
                section.filters.valuesFor("category").forEach { value ->
                    assertTrue(
                        "section '${section.id}' declares category '$value', which the host silently drops",
                        value in speakable,
                    )
                }
            }
        }

    @Test
    fun `the six category shelves ride distinct query presets`() =
        runTest {
            val sections = provider.sections()

            val terms =
                sections
                    .filter { it.id !in setOf("popular", "anime", "girls", "games", "movies") }
                    .map { section ->
                        assertTrue(
                            "section '${section.id}' must ride the query key",
                            section.filters.valuesFor("query").isNotEmpty(),
                        )
                        section.filters.valuesFor("query").first()
                    }
            assertEquals(
                listOf("art", "animals", "cars", "nature", "sports", "travel"),
                terms,
            )
            // Distinct terms, distinct tabs: no two shelves may collapse
            // onto the same feed.
            assertEquals(terms.size, terms.toSet().size)
        }

    @Test
    fun `capabilities declare what the site can honestly serve`() {
        assertEquals(
            setOf(
                com.cloudimage.provider.api.Capability.POPULAR,
                com.cloudimage.provider.api.Capability.SEARCH,
                com.cloudimage.provider.api.Capability.FILTERS,
                com.cloudimage.provider.api.Capability.TAGS,
            ),
            provider.capabilities,
        )
    }
}

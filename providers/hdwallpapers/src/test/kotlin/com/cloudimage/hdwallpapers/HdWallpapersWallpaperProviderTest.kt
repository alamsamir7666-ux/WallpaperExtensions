package com.cloudimage.hdwallpapers

import com.cloudimage.provider.api.Capability
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
 * HD Wallpapers provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the live site's markup: the shared
 * twenty-cell grid with its `-t1`/`-t2` thumb pair, the pagination bars
 * (the grid's `page/` bar and the category page's `pageSub/` sibling it
 * must never read), the apology page with its trending cards, the
 * wallpaper page with its resolution list and tag row, the search page
 * with its own URL shapes, and the flat vocabulary's routing all stay
 * covered without a network.
 */
class HdWallpapersWallpaperProviderTest {
    private val provider = HdWallpapersWallpaperProvider()

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

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    // Fixtures: shapes captured from hdwallpapers.in, trimmed to the
    // parts the parser keys on.

    /**
     * The site-wide navigation every page carries — twenty-four
     * categories and a resolution list. The grid parser must walk past
     * all of it without tripping.
     */
    private val siteNavigation =
        """
        <ul class="menu">
            <li><a href="/latest_wallpapers.html">Latest Wallpapers</a></li>
            <li><a href="/top_download_wallpapers.html">Top Downloads</a></li>
            <li><a href="/top_view_wallpapers.html">Most Popular Wallpapers</a></li>
            <li><a href="/random_wallpapers.html">Random</a></li>
        </ul>
        <div id="tabs-categories">
            <a href="/3d__abstract-desktop-wallpapers.html">3D / Abstract</a>
            <a href="/animals__birds-desktop-wallpapers.html">Animals / Birds</a>
            <a href="/cars-desktop-wallpapers.html">Cars</a>
            <a href="/nature__landscape-desktop-wallpapers.html">Nature / Landscapes</a>
            <a href="/sports-desktop-wallpapers.html">Sports</a>
        </div>
        <div id="tabs-resoloutions">
            <a href="/1920x1080_hd-wallpapers-r.html">1920x1080 Full HD</a>
            <a href="/3840x2160_ultra+hd+4k-wallpapers-r.html">3840x2160 4K</a>
            <a href="/7680x4320_ultra+hd+8k-wallpapers-r.html">7680x4320 8K</a>
        </div>
        """.trimIndent()

    /**
     * A listing page — real cell markup: a JPG cell with its full title
     * in `alt` and `<em1>`, a duplicate of the first cell (dedupe), and a
     * cell whose blank `alt` falls back to `<em1>`.
     */
    private val listingGrid =
        """
        <ul class="wallpapers"><li class="wall" >
<div class="thumb">
<a href="/majestic_snow_leopard_sitting_on_a_rocky_cliff_in_a_snowy_mountain_landscape_hd_snow_leopard-wallpapers.html">
<img src="/thumbs/2026/majestic_snow_leopard_sitting_on_a_rocky_cliff_in_a_snowy_mountain_landscape_hd_snow_leopard-t1.jpg" alt="majestic snow leopard sitting on a rocky cliff in a snowy mountain landscape HD snow leopard"  class="thumb_img" width="246" height="154" srcset="/thumbs/2026/majestic_snow_leopard_sitting_on_a_rocky_cliff_in_a_snowy_mountain_landscape_hd_snow_leopard-t2.jpg 2x" />
<em1>majestic snow leopard sitting on a rocky cliff in a snowy mountain landscape HD snow leopard</em1>
</a>
</div>

</li><li class="wall" >
<div class="thumb">
<a href="/majestic_snow_leopard_sitting_on_a_rocky_cliff_in_a_snowy_mountain_landscape_hd_snow_leopard-wallpapers.html">
<img src="/thumbs/2026/majestic_snow_leopard_sitting_on_a_rocky_cliff_in_a_snowy_mountain_landscape_hd_snow_leopard-t1.jpg" alt="majestic snow leopard sitting on a rocky cliff in a snowy mountain landscape HD snow leopard"  class="thumb_img" width="246" height="154" srcset="/thumbs/2026/majestic_snow_leopard_sitting_on_a_rocky_cliff_in_a_snowy_mountain_landscape_hd_snow_leopard-t2.jpg 2x" />
<em1>majestic snow leopard sitting on a rocky cliff in a snowy mountain landscape HD snow leopard</em1>
</a>
</div>

</li><li class="wall" >
<div class="thumb">
<a href="/2027_subaru_getaway_car_4k_8k_hd_cars-wallpapers.html">
<img src="/thumbs/2026/2027_subaru_getaway_car_4k_8k_hd_cars-t1.jpg" alt=""  class="thumb_img" width="246" height="154" srcset="/thumbs/2026/2027_subaru_getaway_car_4k_8k_hd_cars-t2.jpg 2x" />
<em1>2027 Subaru Getaway Car 4K 8K HD Cars</em1>
</a>
</div>

</li><li class="wall" >
<div class="thumb">
<a href="/giant_panda_eating_bamboo_in_a_lush_green_forest_setting_hd_panda-wallpapers.html">
<img src="/thumbs/2026/giant_panda_eating_bamboo_in_a_lush_green_forest_setting_hd_panda-t1.jpg" alt="giant panda eating bamboo in a lush green forest setting HD panda"  class="thumb_img" width="246" height="154" srcset="/thumbs/2026/giant_panda_eating_bamboo_in_a_lush_green_forest_setting_hd_panda-t2.jpg 2x" />
<em1>giant panda eating bamboo in a lush green forest setting HD panda</em1>
</a>
</div>

</li></ul>
        """.trimIndent()

    /** The pagination bar of a continuing stream — the `Next` link present. */
    private val continuingBar =
        """
        <div class="pagination"><span class="disabled"><i class="fas fa-angle-left"></i> Prev</span><span class="selected">1</span><a href="/latest_wallpapers/page/2">2</a>...<a href="/latest_wallpapers/page/5681">5681</a><a href="/latest_wallpapers/page/2">Next <i class="fas fa-angle-right"></i></a></div>
        """.trimIndent()

    /** The pagination bar of a stream's last page — no `Next` link. */
    private val lastPageBar =
        """
        <div class="pagination"><a href="/latest_wallpapers/page/5680"><i class="fas fa-angle-left"></i> Prev</a><a href="/latest_wallpapers">1</a>...<span class="selected">5681</span></div>
        """.trimIndent()

    /** A continuing listing page: grid plus the continuing bar. */
    private val listingPage = siteNavigation + listingGrid + continuingBar

    /** A stream's last page: grid plus the ending bar. */
    private val lastListingPage = siteNavigation + listingGrid + lastPageBar

    /**
     * The apology page — real shape: the marker first, then the trending
     * collection's twenty cells below it. The trending cards are the
     * site's suggestions and must never be served as results.
     */
    private val apologyPage =
        siteNavigation +
            """
            <div class="page-content wallpaper"> Search Results For `zzxxqqwertynope` Wallpapers - Page 1
            <div class="section-wrapper dark"><div class="section">Latest wallpapers</div></div>
            <div style="width:100%;text-align:center;margin:40px 0 20px">
                <p style="color:#555;font-size:1.5em;margin:0">Sorry, no wallpapers found with your search term. Please try other searches.</p>
            </div>
            <p style="width:100%;text-align:center">Meanwhile please try our trending wallpapers collection.</p>
            <ul class="wallpapers"><li class="wall" >
<div class="thumb">
<a href="/trending_wallpaper_you_must_never_serve-wallpapers.html">
<img src="/thumbs/2026/trending_wallpaper_you_must_never_serve-t1.jpg" alt="trending wallpaper you must never serve"  class="thumb_img" width="246" height="154" srcset="/thumbs/2026/trending_wallpaper_you_must_never_serve-t2.jpg 2x" />
<em1>trending wallpaper you must never serve</em1>
</a>
</div>

</li></ul>
            """.trimIndent()

    /**
     * A category page — real dual-section shape: the sub-category
     * directory above (paginating under `pageSub/`, ITS bar carrying a
     * Next link), the wallpaper grid below (its own `page/` bar, on the
     * LAST page here so the grid's answer must be "the end" — the
     * sub-category bar's Next must never leak into it).
     */
    private val categoryPage =
        siteNavigation +
            """
            <div class="page-content wallpaper"><div class="section-wrapper dark">
            <div class="section">Category Cars - page 1</div>
            </div>
            <ul class="categoryList">
            <li class="wall">
            <a href="/alfa_romeo-desktop-wallpapers.html" title="Alfa Romeo">Alfa Romeo (23)</a>
            </li><li class="wall">
            <a href="/aston_martin-desktop-wallpapers.html" title="Aston Martin">Aston Martin (116)</a>
            </li><li class="wall">
            <a href="/audi-desktop-wallpapers.html" title="Audi">Audi (731)</a>
            </li>
            </ul>
            <div class="pagination"><span class="disabled"><i class="fas fa-angle-left"></i> Prev</span><span class="selected">1</span><a href="/cars-desktop-wallpapers/pageSub/2">2</a><a href="/cars-desktop-wallpapers/pageSub/3">3</a><a href="/cars-desktop-wallpapers/pageSub/2">Next <i class="fas fa-angle-right"></i></a></div>
            <div class="clearboth"></div>
            <div class="section-wrapper dark"><div class="section">Cars wallpapers - page 586</div></div>
            """.trimIndent() +
            listingGrid +
            lastPageBar

    /**
     * A wallpaper page — real item markup: the `h1` with the template's
     * stray close tag, the added-date header, the display thumb beside
     * the original link, the resolution list whose largest entry carries
     * the TRUE dimensions, and the site's own tag row — real tags first,
     * SEO filler last.
     */
    private val wallpaperPage =
        siteNavigation +
            """
            <div class="page-content wallpaper"><article class="item">
            <header>
            <h1 class="title">2027 Subaru Getaway Car 4K 8K HD Cars Wallpaper</a></h1>
            <link rel="canonical" href="https://www.hdwallpapers.in/2027_subaru_getaway_car_4k_8k_hd_cars-wallpapers.html" />
            <div class="section-wrapper review">
                                <div class="section">
                    Added April 28, 2026 in
                                            >
                                                    <a href="/cars-desktop-wallpapers.html"
                                title="Cars wallpapers">Cars</a>
                                                            </div>
            </div>
            </header>

            <div class="overview">
            <a href="/download/2027_subaru_getaway_car_4k_8k_hd_cars-HD.jpg"
                target="_blank" title="2027 Subaru Getaway Car 4K 8K HD Cars, wallpaper" class="wallpaper-thumb">
                <img src="/thumbs/2026/2027_subaru_getaway_car_4k_8k_hd_cars-t2.jpg" alt="2027 Subaru Getaway Car 4K 8K HD Cars"
                    title="2027 Subaru Getaway Car 4K 8K HD Cars" width="520" height="325" /></a>
            </div>
            <br />
            <div class="wallpaper-resolutions">
            <p><i class="fas fa-download"></i> Download this wallpaper from the following resolutions...</p>
            <span>HD resolutions</span>
            <a href="download/2027_subaru_getaway_car_4k_8k_hd_cars-1280x720.jpg" title="HD 1280 x 720 Wallpaper">1280 x 720</a>
            <a href="download/2027_subaru_getaway_car_4k_8k_hd_cars-1920x1080.jpg" title="HD 1920 x 1080 Wallpaper">1920 x 1080</a>
            <a href="download/2027_subaru_getaway_car_4k_8k_hd_cars-2560x1440.jpg" title="HD 2560 x 1440 Wallpaper">2560 x 1440</a>
            <a href="download/2027_subaru_getaway_car_4k_8k_hd_cars-3840x2160.jpg" title="HD 3840 x 2160 Wallpaper">3840 x 2160</a>
            <a target="_blank" href="/download/2027_subaru_getaway_car_4k_8k_hd_cars-HD.jpg" title="Download 2027 Subaru Getaway Car 4K 8K HD Cars Wallpapers"><b>Original</b></a>
            </div>
            <div class="wallinfo">
            <div class="license stockwalldet"><i class="fas fa-caret-right"></i> <b>Tags:</b>
            <a href="/tag/subaru.html" title="Subaru wallpapers">Subaru</a>
            <a href="/tag/getaway.html" title="getaway wallpapers">getaway</a>
            <a href="/tag/car.html" title="car wallpapers">car</a>
            <a href="/tag/hd_wallpapers.html" title="hd wallpapers wallpapers">hd wallpapers</a>
            <a href="/tag/free_download_hd_wallpapers.html" title="free download hd wallpapers wallpapers">free download hd wallpapers</a>
            <a href="/tag/free_hd_car_wallpapers.html" title="free hd car wallpapers wallpapers">free hd car wallpapers</a>
            </div>
            </div>
            </article>
            """.trimIndent()

    /** A search answer — the same grid under the search URL space. */
    private val searchPage =
        """
        <h1 class="ui centered aligned">Search result for cars</h1>
        """.trimIndent() +
            listingGrid +
            """
            <div class="pagination"><span class="disabled"><i class="fas fa-angle-left"></i> Prev</span><span class="selected">1</span><a href="/search/page/2?q=cars">2</a>...<a href="/search/page/568?q=cars">568</a><a href="/search/page/2?q=cars">Next <i class="fas fa-angle-right"></i></a></div>
            """.trimIndent()

    /** A page that no longer speaks the shape — a redesign or a redirect. */
    private val unrecognizedPage =
        """
        <html><body><h1>Wallpaper not found</h1><p>This wallpaper has been removed.</p></body></html>
        """.trimIndent()

    // ------------------------------------------------------------- feeds

    @Test
    fun popularServesTheMostViewedRanking() =
        runTest {
            val client = configureWith(mapOf("https://www.hdwallpapers.in/top_view_wallpapers" to ok(listingPage)))

            val page1 = provider.popular(page = 1).getOrThrow()

            assertEquals(
                listOf(
                    "https://www.hdwallpapers.in/top_view_wallpapers",
                ),
                client.requests,
            )
            assertEquals(3, page1.wallpapers.size) // four cells, one duplicate slug
            assertEquals(2, page1.nextPage)
            assertEquals("cloudimage.hdwallpapers", page1.wallpapers.first().providerId)
        }

    @Test
    fun toplistSortingWalksTheDownloadsRanking() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.hdwallpapers.in/top_download_wallpapers/page/2" to ok(lastListingPage),
                        "https://www.hdwallpapers.in/top_download_wallpapers" to ok(listingPage),
                    ),
                )

            val page1 = provider.popular(page = 1, filters = Filters.of("sorting" to "toplist")).getOrThrow()
            assertEquals(2, page1.nextPage)

            val page2 = provider.popular(page = 2, filters = Filters.of("sorting" to "toplist")).getOrThrow()
            assertNull(page2.nextPage) // the bar carries no Next — the site's own stop
            assertEquals(
                listOf(
                    "https://www.hdwallpapers.in/top_download_wallpapers",
                    "https://www.hdwallpapers.in/top_download_wallpapers/page/2",
                ),
                client.requests,
            )
        }

    @Test
    fun dateSortingWalksTheLatestFeed() =
        runTest {
            val client =
                configureWith(mapOf("https://www.hdwallpapers.in/latest_wallpapers" to ok(listingPage)))

            val page1 = provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()

            assertEquals(listOf("https://www.hdwallpapers.in/latest_wallpapers"), client.requests)
            assertEquals(3, page1.wallpapers.size)
            assertEquals(2, page1.nextPage)
        }

    @Test
    fun categoryFilterWalksTheNativeAnimeCategory() =
        runTest {
            val client =
                configureWith(mapOf("https://www.hdwallpapers.in/anime-desktop-wallpapers" to ok(categoryPage)))

            val page = provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals(
                listOf("https://www.hdwallpapers.in/anime-desktop-wallpapers"),
                client.requests,
            )
            // The grid's own ending bar answers, never the sub-category's
            // continuing one — the stream is honestly finished.
            assertEquals(3, page.wallpapers.size)
            assertNull(page.nextPage)
        }

    @Test
    fun unknownCategoryValuesFallBackToTheDefaultFeed() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.hdwallpapers.in/top_view_wallpapers" to ok(listingPage),
                    ),
                )

            provider.popular(page = 1, filters = Filters.of("category" to "people")).getOrThrow()

            // The site files no people category; the default feed serves,
            // the contract's documented behavior.
            assertEquals(
                listOf("https://www.hdwallpapers.in/top_view_wallpapers"),
                client.requests,
            )
        }

    @Test
    fun randomSortingServesOneRandomLatestBatch() =
        runTest {
            val client =
                configureWith(mapOf("https://www.hdwallpapers.in/latest_wallpapers" to ok(listingPage)))

            val page = provider.popular(page = 1, filters = Filters.of("sorting" to "random")).getOrThrow()

            assertEquals(3, page.wallpapers.size)
            assertNull(page.nextPage) // one batch, no continuation
            assertEquals(1, client.requests.size) // one fetch, wherever it landed
            assertTrue(client.requests.single().startsWith("https://www.hdwallpapers.in/latest_wallpapers"))
        }

    @Test
    fun deepPaginationIsCapped() =
        runTest {
            val client = configureWith(emptyMap())

            val pageZero = provider.popular(page = 0).getOrThrow()
            val pageHundredAndOne = provider.popular(page = 101).getOrThrow()

            assertTrue(pageZero.wallpapers.isEmpty())
            assertNull(pageZero.nextPage)
            assertTrue(pageHundredAndOne.wallpapers.isEmpty())
            assertNull(pageHundredAndOne.nextPage)
            assertTrue(client.requests.isEmpty())
        }

    @Test
    fun pastTheEndApologizesAndNeverServesTheTrendingSuggestions() =
        runTest {
            val client =
                configureWith(mapOf("https://www.hdwallpapers.in/top_view_wallpapers" to ok(apologyPage)))

            val page = provider.popular(page = 99).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertEquals(
                listOf("https://www.hdwallpapers.in/top_view_wallpapers/page/99"),
                client.requests,
            )
        }

    // ------------------------------------------------------------- search

    @Test
    fun searchServesTheSitesOwnAnswerWithItsOwnUrlShapes() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.hdwallpapers.in/search/page/2?q=cars" to ok(searchPage),
                        "https://www.hdwallpapers.in/search.html?q=cars" to ok(searchPage),
                    ),
                )

            val page1 = provider.search("cars", page = 1).getOrThrow()
            val page2 = provider.search("cars", page = 2).getOrThrow()

            assertEquals(
                listOf(
                    "https://www.hdwallpapers.in/search.html?q=cars",
                    "https://www.hdwallpapers.in/search/page/2?q=cars",
                ),
                client.requests,
            )
            assertEquals(3, page1.wallpapers.size)
            assertEquals(2, page1.nextPage)
            assertEquals(3, page2.wallpapers.size)
        }

    @Test
    fun multiWordQueriesRideTheSitesOwnEncoding() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://www.hdwallpapers.in/search.html?q=snow+leopard" to ok(searchPage)),
                )

            provider.search("snow leopard", page = 1).getOrThrow()

            assertEquals(
                listOf("https://www.hdwallpapers.in/search.html?q=snow+leopard"),
                client.requests,
            )
        }

    @Test
    fun searchZeroResultApologyStaysEmpty() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.hdwallpapers.in/search.html?q=zzxxqqwertynope" to ok(apologyPage),
                    ),
                )

            val page = provider.search("zzxxqqwertynope", page = 1).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            // The trending card behind the apology was never served.
            assertTrue(page.wallpapers.none { it.id == "trending_wallpaper_you_must_never_serve" })
        }

    @Test
    fun blankSearchLandsOnTheLatestFeed() =
        runTest {
            val client =
                configureWith(mapOf("https://www.hdwallpapers.in/latest_wallpapers" to ok(listingPage)))

            val page = provider.search("", page = 1).getOrThrow()

            assertEquals(listOf("https://www.hdwallpapers.in/latest_wallpapers"), client.requests)
            assertEquals(3, page.wallpapers.size)
        }

    // ------------------------------------------------------------ mapping

    @Test
    fun gridItemsCarryTheSitesTwoXThumbsAndWorkingOriginals() =
        runTest {
            configureWith(mapOf("https://www.hdwallpapers.in/top_view_wallpapers" to ok(listingPage)))

            val page = provider.popular(page = 1).getOrThrow()

            val leopard = page.wallpapers.first { it.id.startsWith("majestic_snow_leopard") }
            assertEquals(
                "https://www.hdwallpapers.in/thumbs/2026/majestic_snow_leopard_sitting_on_a_rocky_cliff_in_a_snowy_mountain_landscape_hd_snow_leopard-t2.jpg",
                leopard.thumbUrl,
            )
            assertEquals(
                "https://www.hdwallpapers.in/download/majestic_snow_leopard_sitting_on_a_rocky_cliff_in_a_snowy_mountain_landscape_hd_snow_leopard-HD.jpg",
                leopard.fullUrl,
            )
            assertEquals(
                "majestic snow leopard sitting on a rocky cliff in a snowy mountain landscape HD snow leopard",
                leopard.title,
            )
            // The blank-alt cell falls back to its em1 title.
            val subaru = page.wallpapers.first { it.id == "2027_subaru_getaway_car_4k_8k_hd_cars" }
            assertEquals("2027 Subaru Getaway Car 4K 8K HD Cars", subaru.title)
        }

    @Test
    fun gridDimensionsStayHonestlyAbsent() =
        runTest {
            configureWith(mapOf("https://www.hdwallpapers.in/top_view_wallpapers" to ok(listingPage)))

            val page = provider.popular(page = 1).getOrThrow()

            // The listing's 246x154 attributes are the uniform card crop,
            // true of no wallpaper — null is the honest answer.
            assertTrue(page.wallpapers.all { it.width == null && it.height == null })
        }

    // ------------------------------------------------------------ details

    @Test
    fun detailsServeTheDefinitiveRecord() =
        runTest {
            configureWith(
                mapOf("https://www.hdwallpapers.in/2027_subaru_getaway_car_4k_8k_hd_cars-wallpapers.html" to ok(wallpaperPage)),
            )

            val details =
                provider.details("2027_subaru_getaway_car_4k_8k_hd_cars").getOrThrow()

            assertEquals("2027 Subaru Getaway Car 4K 8K HD Cars", details.wallpaper.title)
            assertEquals("3840x2160", details.resolution)
            assertEquals(3840, details.wallpaper.width)
            assertEquals(2160, details.wallpaper.height)
            assertEquals(
                "https://www.hdwallpapers.in/download/2027_subaru_getaway_car_4k_8k_hd_cars-HD.jpg",
                details.wallpaper.fullUrl,
            )
            assertEquals(
                "https://www.hdwallpapers.in/thumbs/2026/2027_subaru_getaway_car_4k_8k_hd_cars-t2.jpg",
                details.wallpaper.thumbUrl,
            )
            assertEquals(
                "https://www.hdwallpapers.in/2027_subaru_getaway_car_4k_8k_hd_cars-wallpapers.html",
                details.sourceUrl,
            )
            // The site's real tags answer; the SEO filler never does.
            assertEquals(listOf("Subaru", "getaway", "car"), details.wallpaper.tags)
        }

    @Test
    fun detailsRejectUnrecognizedPages() =
        runTest {
            configureWith(
                mapOf("https://www.hdwallpapers.in/gone-wallpapers.html" to ok(unrecognizedPage)),
            )

            val result = provider.details("gone")

            assertTrue(result.isFailure)
        }

    @Test
    fun detailsRejectPagesAnsweringForAnotherSlug() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.hdwallpapers.in/redirected-wallpapers.html" to ok(wallpaperPage),
                ),
            )

            val result = provider.details("redirected")

            // The page answered for another wallpaper — a source failure,
            // never a record minted under the wrong id.
            assertTrue(result.isFailure)
        }

    // -------------------------------------------------------------- extras

    @Test
    fun suggestTagsComeOnlyFromSeenTags() =
        runTest {
            configureWith(mapOf("https://www.hdwallpapers.in/top_view_wallpapers" to ok(listingPage)))

            // A fresh instance answers nothing.
            assertTrue(provider.suggestTags("leopard").getOrThrow().isEmpty())

            provider.popular(page = 1).getOrThrow()
            val suggestions = provider.suggestTags("leopard").getOrThrow()

            assertEquals(listOf("leopard"), suggestions)
        }

    @Test
    fun sectionsOfferTheFifteenShelves() =
        runTest {
            val sections = provider.sections()

            assertEquals(15, sections.size)
            assertEquals("popular", sections[0].id)
            assertEquals("latest", sections[1].id)
            assertTrue(sections[1].filters.isSelected("sorting", "date"))
            assertEquals("anime", sections[2].id)
            assertTrue(sections[2].filters.isSelected("category", "anime"))
            // The tag-style presets ride the host's query vocabulary.
            assertTrue(sections[3].filters.isSelected("query", "cars"))
            assertTrue(sections.last().filters.isSelected("query", "cute"))
        }

    @Test
    fun capabilitiesDeclareTheFlatFeedSurface() {
        assertTrue(Capability.POPULAR in provider.capabilities)
        assertTrue(Capability.LATEST in provider.capabilities)
        assertTrue(Capability.SEARCH in provider.capabilities)
        assertTrue(Capability.TAGS in provider.capabilities)
        assertTrue(Capability.FILTERS in provider.capabilities)
        assertTrue(Capability.RANDOM !in provider.capabilities)
        assertTrue(Capability.ALBUMS !in provider.capabilities)
        assertEquals("cloudimage.hdwallpapers", provider.meta.id)
    }
}

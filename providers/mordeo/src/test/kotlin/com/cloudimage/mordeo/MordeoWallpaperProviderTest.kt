package com.cloudimage.mordeo

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
 * Mordeo provider over a scripted fake of the plugin-facing HTTP facade,
 * with fixtures cut from the live site's markup: the shared six-cell
 * masonry with its four eras of card images (2016 preview, 2017 sized
 * jpg, 2019 aspect variant, 2026 webp with its `-scaled` copy), the
 * wp-pagenavi bar whose `nextpostslink` is the cursor, the empty
 * search page, the three eras of wallpaper pages (direct download link,
 * octet-stream endpoint, preview display), the canonical cross-check,
 * and the flat vocabulary's routing all stay covered without a network.
 */
class MordeoWallpaperProviderTest {
    private val provider = MordeoWallpaperProvider()

    /**
     * URL-routed responses: an exact match wins, then the first prefix
     * match in insertion order; unmatched URLs answer 500 to fail loudly.
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

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    // Fixtures: shapes captured from mordeo.org, trimmed to the parts the
    // parser keys on.

    /** The chrome every page carries and the parser must walk past. */
    private val siteChrome =
        """
        <html><head><title>Mordeo</title>
        <link rel='dns-prefetch' href='//fonts.googleapis.com' />
        <style>.top-navigation { background-color: #171717 !important; }</style>
        </head><body>
        <header><ul class="jquerycssmenu">
            <li><a href="https://www.mordeo.org/wallpapers/category/abstract/">Abstract</a></li>
            <li><a href="https://www.mordeo.org/wallpapers/category/anime/">Anime</a></li>
            <li><a href="https://www.mordeo.org/wallpapers/category/movie/">Movie</a></li>
            <li><a href="https://www.mordeo.org/wallpapers/category/people/">People</a></li>
        </ul></header>
        """.trimIndent()

    /** A 2026-era card: webp thumbs, full srcset with the `-scaled` copy. */
    private val cardDwayne =
        """
        <li class="post-31819 post type-post status-publish format-standard has-post-thumbnail hentry category-movie tag-dwayne-johnson tag-moana-2" id="post-31819">
            <div class="post-date"><span class="month">Jul</span> <span class="day">08</span></div>
            <a href="https://www.mordeo.org/wallpapers/dwayne-johnson-in-moana-2026-movie/"> <img width="283" height="503" src="https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-283x503.webp" class="attachment-thumbnail-masonry size-thumbnail-masonry wp-post-image" alt="Dwayne Johnson In Moana 2026 Movie 4K Ultra HD Mobile Wallpaper" decoding="async" fetchpriority="high" srcset="https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-283x503.webp 283w, https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-169x300.webp 169w, https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-576x1024.webp 576w, https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-768x1365.webp 768w, https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-1152x2048.webp 1152w, https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-950x1689.webp 950w, https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-scaled.webp 1440w" sizes="(max-width: 283px) 100vw, 283px" /></a>
            <div class="clear"></div>
            <div class="an-content">
                <h2 class="article-title"><a href="https://www.mordeo.org/wallpapers/dwayne-johnson-in-moana-2026-movie/">Dwayne Johnson In Moana 2026 Movie</a></h2>
                <p style="color:#393939">by <a href="https://www.mordeo.org/wallpapers/author/thomas/" title="Posts by Thomas" rel="author">Thomas</a> in <a href="https://www.mordeo.org/wallpapers/category/movie/">Movie</a> </p>
            </div>
        </li>
        """.trimIndent()

    /** A second 2026-era card: entities in title and alt, the 864w copy. */
    private val cardCatherine =
        """
        <li class="post-31816 post type-post status-publish format-standard has-post-thumbnail hentry category-movie tag-catherine-lagaaia tag-moana-2" id="post-31816">
            <div class="post-date"><span class="month">Jul</span> <span class="day">08</span></div>
            <a href="https://www.mordeo.org/wallpapers/catherine-lagaaia-in-moana-2026-movie/"> <img fetchpriority="high" width="283" height="503" src="https://www.mordeo.org/files/uploads/2026/07/Catherine-Lagaaia-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-283x503.webp" class="attachment-thumbnail-masonry size-thumbnail-masonry wp-post-image" alt="Catherine Laga&#039;aia In Moana 2026 Movie 4K Ultra HD Mobile Wallpaper" decoding="async" srcset="https://www.mordeo.org/files/uploads/2026/07/Catherine-Lagaaia-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-283x503.webp 283w, https://www.mordeo.org/files/uploads/2026/07/Catherine-Lagaaia-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-169x300.webp 169w, https://www.mordeo.org/files/uploads/2026/07/Catherine-Lagaaia-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-576x1024.webp 576w, https://www.mordeo.org/files/uploads/2026/07/Catherine-Lagaaia-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-768x1365.webp 768w, https://www.mordeo.org/files/uploads/2026/07/Catherine-Lagaaia-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-scaled.webp 1440w" sizes="(max-width: 283px) 100vw, 283px" /></a>
            <div class="clear"></div>
            <div class="an-content">
                <h2 class="article-title"><a href="https://www.mordeo.org/wallpapers/catherine-lagaaia-in-moana-2026-movie/">Catherine Laga&#8217;aia In Moana 2026 Movie</a></h2>
                <p style="color:#393939">by <a href="https://www.mordeo.org/wallpapers/author/thomas/" title="Posts by Thomas" rel="author">Thomas</a> in <a href="https://www.mordeo.org/wallpapers/category/movie/">Movie</a> </p>
            </div>
        </li>
        """.trimIndent()

    /** A 2026-era card whose title carries a WordPress entity. */
    private val cardBilly =
        """
        <li class="post-31813 post type-post status-publish format-standard has-post-thumbnail hentry category-tv-series tag-billy-butcher tag-homelander tag-the-boys" id="post-31813">
            <a href="https://www.mordeo.org/wallpapers/billy-butcher-vs-homelander-the-ultimate-face-off/"> <img width="283" height="503" src="https://www.mordeo.org/files/uploads/2026/07/Billy-Butcher-vs-Homelander-The-Ultimate-Face-Off-4K-Ultra-HD-Mobile-Wallpaper-283x503.webp" class="attachment-thumbnail-masonry size-thumbnail-masonry wp-post-image" alt="Billy Butcher vs Homelander - The Ultimate Face-Off 4K Ultra HD Mobile Wallpaper" decoding="async" srcset="https://www.mordeo.org/files/uploads/2026/07/Billy-Butcher-vs-Homelander-The-Ultimate-Face-Off-4K-Ultra-HD-Mobile-Wallpaper-283x503.webp 283w, https://www.mordeo.org/files/uploads/2026/07/Billy-Butcher-vs-Homelander-The-Ultimate-Face-Off-4K-Ultra-HD-Mobile-Wallpaper-169x300.webp 169w, https://www.mordeo.org/files/uploads/2026/07/Billy-Butcher-vs-Homelander-The-Ultimate-Face-Off-4K-Ultra-HD-Mobile-Wallpaper-576x1024.webp 576w, https://www.mordeo.org/files/uploads/2026/07/Billy-Butcher-vs-Homelander-The-Ultimate-Face-Off-4K-Ultra-HD-Mobile-Wallpaper-scaled.webp 1440w" sizes="(max-width: 283px) 100vw, 283px" /></a>
            <div class="clear"></div>
            <div class="an-content">
                <h2 class="article-title"><a href="https://www.mordeo.org/wallpapers/billy-butcher-vs-homelander-the-ultimate-face-off/">Billy Butcher vs Homelander &#8211; The Ultimate Face-Off</a></h2>
            </div>
        </li>
        """.trimIndent()

    /** A 2017-era card: jpg thumbs at the 283x453 aspect, srcset to 1000w. */
    private val cardPathfinder =
        """
        <li class="post-10559 post type-post status-publish format-standard has-post-thumbnail hentry category-game tag-games tag-pathfinder-kingmaker" id="post-10559">
            <a href="https://www.mordeo.org/wallpapers/pathfinder-kingmaker/"> <img width="283" height="453" src="https://www.mordeo.org/files/uploads/2017/12/Pathfinder-Kingmaker-HD-Mobile-Wallpaper-283x453.jpg" class="attachment-thumbnail-masonry size-thumbnail-masonry wp-post-image" alt="Pathfinder Kingmaker HD Mobile Wallpaper" decoding="async" fetchpriority="high" srcset="https://www.mordeo.org/files/uploads/2017/12/Pathfinder-Kingmaker-HD-Mobile-Wallpaper-283x453.jpg 283w, https://www.mordeo.org/files/uploads/2017/12/Pathfinder-Kingmaker-HD-Mobile-Wallpaper-188x300.jpg 188w, https://www.mordeo.org/files/uploads/2017/12/Pathfinder-Kingmaker-HD-Mobile-Wallpaper-768x1229.jpg 768w, https://www.mordeo.org/files/uploads/2017/12/Pathfinder-Kingmaker-HD-Mobile-Wallpaper-640x1024.jpg 640w, https://www.mordeo.org/files/uploads/2017/12/Pathfinder-Kingmaker-HD-Mobile-Wallpaper-950x1520.jpg 950w, https://www.mordeo.org/files/uploads/2017/12/Pathfinder-Kingmaker-HD-Mobile-Wallpaper.jpg 1000w" sizes="(max-width: 283px) 100vw, 283px" /></a>
            <div class="clear"></div>
            <div class="an-content">
                <h2 class="article-title"><a href="https://www.mordeo.org/wallpapers/pathfinder-kingmaker/">Pathfinder Kingmaker</a></h2>
            </div>
        </li>
        """.trimIndent()

    /** A 2016-era card: the hand-made preview image, no larger copies. */
    private val cardCharistmas =
        """
        <li class="post-2941 post type-post status-publish format-standard has-post-thumbnail hentry category-others category-people tag-others tag-peoples" id="post-2941">
            <a href="https://www.mordeo.org/wallpapers/charistmas-candle-light-lamp/"> <img width="250" height="400" src="https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper-Preview.jpg" class="attachment-thumbnail-masonry size-thumbnail-masonry wp-post-image" alt="Charistmas Candle Light Lamp Mobile Wallpaper Preview" decoding="async" fetchpriority="high" srcset="https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper-Preview.jpg 250w, https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper-Preview-188x300.jpg 188w" sizes="(max-width: 250px) 100vw, 250px" /></a>
            <div class="clear"></div>
            <div class="an-content">
                <h2 class="article-title"><a href="https://www.mordeo.org/wallpapers/charistmas-candle-light-lamp/">Charistmas Candle Light Lamp</a></h2>
            </div>
        </li>
        """.trimIndent()

    /** The wp-pagenavi bar with its Next link — the stream continues. */
    private val paginationWithNext =
        """
        <div class='wp-pagenavi' role='navigation'>
        <span class='pages'>Page 1 of 1144</span><span aria-current='page' class='current'>1</span>
        <a class="page larger" title="Page 2" href="https://www.mordeo.org/page/2/">2</a>
        <a class="page larger" title="Page 3" href="https://www.mordeo.org/page/3/">3</a>
        <a class="nextpostslink" rel="next" aria-label="Next Page" href="https://www.mordeo.org/page/2/">»</a>
        <a class="last" aria-label="Last Page" href="https://www.mordeo.org/page/1144/">»</a>
        </div>
        """.trimIndent()

    /** The last page of a stream — no Next link, no more. */
    private val paginationAtEnd =
        """
        <div class='wp-pagenavi' role='navigation'>
        <span class='pages'>Page 153 of 153</span>
        <a class="page smaller" title="Page 152" href="https://www.mordeo.org/wallpapers/category/movie/page/152/">152</a>
        <span aria-current='page' class='current'>153</span>
        </div>
        """.trimIndent()

    /** A listing page: the chrome, the masonry cards, the bar. */
    private fun listingPage(
        cards: String,
        pagination: String,
    ): String =
        "$siteChrome\n<ul id=\"masonry_list\" class=\"classic-blog js-masonry\" data-masonry-options='{ \"columnWidth\": 1 }'>\n$cards\n</ul><!-- end .classic-blog -->\n$pagination\n</body></html>"

    /** The stream's first page: every era, plus a duplicate cell. */
    private val streamFirstPage =
        listingPage(
            cardDwayne + "\n" + cardDwayne + "\n" + cardCatherine + "\n" + cardBilly + "\n" +
                cardPathfinder + "\n" + cardCharistmas,
            paginationWithNext,
        )

    /** The stream's last page: no Next link. */
    private val streamLastPage = listingPage(cardCharistmas, paginationAtEnd)

    /** The empty search page: the masonry list itself empty. */
    private val emptySearchPage =
        """
        $siteChrome
        <div class="archive-header"><h3>Search Results for: <strong>zzzqqqxxx</strong></h3></div>
        <ul id="masonry_list" class="classic-blog js-masonry"  data-masonry-options='{ "columnWidth": 1 }'>               </ul><!-- end .classic-blog -->
        <div class="line-bottom"></div>
        </body></html>
        """.trimIndent()

    /** A wallpaper page of the 2026 era: a direct download link. */
    private val detailNewEra =
        """
        <html><head><title>Dwayne Johnson In Moana 2026 Movie 4K Mobile Wallpaper</title>
        <link rel="canonical" href="https://www.mordeo.org/wallpapers/dwayne-johnson-in-moana-2026-movie/" />
        </head><body>
        <h1 class="article-title">Dwayne Johnson In Moana 2026 Movie</h1>
        <img width="950" height="1689" src="https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-950x1689.webp" class="attachment-thumbnail-single-image size-thumbnail-single-image wp-post-image" alt="Dwayne Johnson In Moana 2026 Movie 4K Ultra HD Mobile Wallpaper" decoding="async" fetchpriority="high" srcset="https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-950x1689.webp 950w, https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper-scaled.webp 1440w" sizes="(max-width: 950px) 100vw, 950px" />
        <div class="p-first-letter">
        <p style="text-align: center;">Follow the link below to download &#8220;<strong>Dwayne Johnson In Moana 2026 Movie</strong>&#8221; 4K Ultra HD mobile wallpaper for free on your mobile phones, android phones and iPhones.</p>
        <p style="text-align: center;"><a href="https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-4K-Ultra-HD-Mobile-Wallpaper.jpg" class="simplebtn black" target="_blank">Download Wallpaper In 4K Ultra HD</a>
        <p align="center">"<strong>Dwayne Johnson In Moana 2026 Movie</strong>" from the "<a href="https://www.mordeo.org/wallpapers/category/movie/" rel="category tag">Movie</a>" category featuring " &middot; <a href="https://www.mordeo.org/wallpapers/tag/dwayne-johnson/" rel="tag">Dwayne Johnson</a>, <a href="https://www.mordeo.org/wallpapers/tag/moana-2/" rel="tag">Moana 2</a>" is uploaded by "<a href="https://www.mordeo.org/wallpapers/author/thomas/" title="Posts by Thomas" rel="author">Thomas</a>". This wallpaper is free to download for personal and non-commercial use. With 2160x3840 pixels (<strong>4K Ultra HD Mobile Resolution</strong>), it’s perfectly optimized for most modern smartphones.</p>
        </div>
        </body></html>
        """.trimIndent()

    /** A wallpaper page of the 2019 era: the octet-stream download endpoint. */
    private val detailMidEra =
        """
        <html><head><title>Annabelle Doll Comes Home 2019 4K Mobile Wallpaper</title>
        <link rel="canonical" href="https://www.mordeo.org/wallpapers/annabelle-doll-comes-home-2019/" />
        </head><body>
        <h1 class="article-title">Annabelle Doll Comes Home 2019</h1>
        <img width="950" height="1689" src="https://www.mordeo.org/files/uploads/2019/06/Annabelle-Doll-Comes-Home-2019-4K-Ultra-HD-Mobile-Wallpaper-950x1689.jpg" class="attachment-thumbnail-single-image size-thumbnail-single-image wp-post-image" alt="Annabelle Doll Comes Home 2019 4K Ultra HD Mobile Wallpaper" decoding="async" fetchpriority="high" srcset="https://www.mordeo.org/files/uploads/2019/06/Annabelle-Doll-Comes-Home-2019-4K-Ultra-HD-Mobile-Wallpaper-950x1689.jpg 950w" sizes="(max-width: 950px) 100vw, 950px" />
        <div class="p-first-letter">
        <p style="text-align: center;"><a href="/download/18443/" class="simplebtn blue" target="_blank">Download Wallpaper In 4K Ultra HD</a>
        <p align="center">"<strong>Annabelle Doll Comes Home 2019</strong>" from the "<a href="https://www.mordeo.org/wallpapers/category/movie/" rel="category tag">Movie</a>" category featuring " &middot; <a href="https://www.mordeo.org/wallpapers/tag/annabelle-comes-home/" rel="tag">Annabelle Comes Home</a>" is uploaded by "<a href="https://www.mordeo.org/wallpapers/author/charlie/" title="Posts by Charlie" rel="author">Charlie</a>". This wallpaper is free to download for personal and non-commercial use. With 2160x3840 pixels (<strong>4K Ultra HD Mobile Resolution</strong>), it’s perfectly optimized for most modern smartphones.</p>
        </div>
        </body></html>
        """.trimIndent()

    /** A wallpaper page of the 2016 era: the preview as its display image. */
    private val detailOldEra =
        """
        <html><head><title>Charistmas Candle Light Lamp Mobile Wallpaper</title>
        <link rel="canonical" href="https://www.mordeo.org/wallpapers/charistmas-candle-light-lamp/" />
        </head><body>
        <h1 class="article-title">Charistmas Candle Light Lamp</h1>
        <img width="250" height="400" src="https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper-Preview.jpg" class="attachment-thumbnail-single-image size-thumbnail-single-image wp-post-image" alt="Charistmas Candle Light Lamp Mobile Wallpaper Preview" decoding="async" fetchpriority="high" srcset="https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper-Preview.jpg 250w" sizes="(max-width: 250px) 100vw, 250px" />
        <div class="p-first-letter">
        <p style="text-align: center;"><a href="https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper.jpg" class="simplebtn blue" target="_blank">Download Wallpaper</a>
        <p align="center">"<strong>Charistmas Candle Light Lamp</strong>" from the "<a href="https://www.mordeo.org/wallpapers/category/others/" rel="category tag">Others</a> &middot; <a href="https://www.mordeo.org/wallpapers/category/people/" rel="category tag">People</a>" category featuring " &middot; <a href="https://www.mordeo.org/wallpapers/tag/others/" rel="tag">others</a>, <a href="https://www.mordeo.org/wallpapers/tag/peoples/" rel="tag">peoples</a>" is uploaded by "<a href="https://www.mordeo.org/wallpapers/author/lucy/" title="Posts by Lucy" rel="author">Lucy</a>". This wallpaper is free to download for personal and non-commercial use. With 2160x3840 pixels (<strong>4K Ultra HD Mobile Resolution</strong>), it’s perfectly optimized for most modern smartphones.</p>
        </div>
        </body></html>
        """.trimIndent()

    // ------------------------------------------------------------- stream

    @Test
    fun theStreamServesEveryEraOfCardWithDeterministicOriginals() =
        runTest {
            val client = configureWith(mapOf("https://www.mordeo.org/" to ok(streamFirstPage)))

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals("https://www.mordeo.org/", client.requests.single())
            assertEquals(5, page.wallpapers.size)
            assertEquals(2, page.nextPage)
            val dwayne = page.wallpapers[0]
            assertEquals("dwayne-johnson-in-moana-2026-movie", dwayne.id)
            assertEquals(
                "https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-" +
                    "4K-Ultra-HD-Mobile-Wallpaper-576x1024.webp",
                dwayne.thumbUrl,
            )
            assertEquals(
                "https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-" +
                    "4K-Ultra-HD-Mobile-Wallpaper.jpg",
                dwayne.fullUrl,
            )
            assertEquals("Dwayne Johnson In Moana 2026 Movie", dwayne.title)
            assertEquals(listOf("Dwayne Johnson", "Moana 2"), dwayne.tags)
            assertNull(dwayne.width)
            assertNull(dwayne.height)
        }

    @Test
    fun theCardsCarryTheSitesOwnTagsFromTheirClasses() =
        runTest {
            configureWith(mapOf("https://www.mordeo.org/" to ok(streamFirstPage)))

            val page = provider.popular(page = 1).getOrThrow()

            val billy = page.wallpapers.first { it.id == "billy-butcher-vs-homelander-the-ultimate-face-off" }
            assertEquals(listOf("Billy Butcher", "Homelander", "The Boys"), billy.tags)
            val pathfinder = page.wallpapers.first { it.id == "pathfinder-kingmaker" }
            assertEquals(listOf("Games", "Pathfinder Kingmaker"), pathfinder.tags)
        }

    @Test
    fun titlesDecodeTheirWordPressEntities() =
        runTest {
            configureWith(mapOf("https://www.mordeo.org/" to ok(streamFirstPage)))

            val page = provider.popular(page = 1).getOrThrow()

            val billy = page.wallpapers.first { it.id == "billy-butcher-vs-homelander-the-ultimate-face-off" }
            assertEquals("Billy Butcher vs Homelander – The Ultimate Face-Off", billy.title)
            // The numeric apostrophes of both encodings decode alike.
            val catherine = page.wallpapers.first { it.id == "catherine-lagaaia-in-moana-2026-movie" }
            assertEquals("Catherine Laga’aia In Moana 2026 Movie", catherine.title)
        }

    @Test
    fun aspectVariantCardsPickTheirOwnTwoXThumb() =
        runTest {
            configureWith(mapOf("https://www.mordeo.org/" to ok(streamFirstPage)))

            val page = provider.popular(page = 1).getOrThrow()

            val pathfinder = page.wallpapers.first { it.id == "pathfinder-kingmaker" }
            assertEquals(
                "https://www.mordeo.org/files/uploads/2017/12/Pathfinder-Kingmaker-HD-Mobile-Wallpaper-640x1024.jpg",
                pathfinder.thumbUrl,
            )
            assertEquals(
                "https://www.mordeo.org/files/uploads/2017/12/Pathfinder-Kingmaker-HD-Mobile-Wallpaper.jpg",
                pathfinder.fullUrl,
            )
        }

    @Test
    fun previewCardsKeepTheirOwnImageAndDeriveTheirOriginal() =
        runTest {
            configureWith(mapOf("https://www.mordeo.org/" to ok(streamFirstPage)))

            val page = provider.popular(page = 1).getOrThrow()

            val charistmas = page.wallpapers.first { it.id == "charistmas-candle-light-lamp" }
            assertEquals(
                "https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper-Preview.jpg",
                charistmas.thumbUrl,
            )
            assertEquals(
                "https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper.jpg",
                charistmas.fullUrl,
            )
        }

    @Test
    fun duplicateCellsDedupeBySlug() =
        runTest {
            configureWith(mapOf("https://www.mordeo.org/" to ok(streamFirstPage)))

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(1, page.wallpapers.count { it.id == "dwayne-johnson-in-moana-2026-movie" })
        }

    @Test
    fun theStreamWalksItsOwnPageShapes() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.mordeo.org/" to ok(streamFirstPage),
                        "https://www.mordeo.org/page/2/" to ok(streamLastPage),
                    ),
                )

            val second = provider.popular(page = 2).getOrThrow()

            assertEquals("https://www.mordeo.org/page/2/", client.requests.single())
            assertEquals(1, second.wallpapers.size)
            assertNull(second.nextPage)
        }

    @Test
    fun deepScrollsAreCappedWithoutRequests() =
        runTest {
            val client = configureWith(mapOf("https://www.mordeo.org/" to ok(streamFirstPage)))

            val page = provider.popular(page = 101).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue(client.requests.isEmpty())
        }

    // ------------------------------------------------------------ category

    @Test
    fun categoryFiltersRideTheHostVocabularyNatively() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.mordeo.org/wallpapers/category/anime/" to ok(streamFirstPage),
                        "https://www.mordeo.org/wallpapers/category/people/" to ok(streamFirstPage),
                        "https://www.mordeo.org/wallpapers/category/anime/page/2/" to ok(streamLastPage),
                        "https://www.mordeo.org/" to ok(streamFirstPage),
                    ),
                )

            val anime = provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            val people = provider.popular(page = 1, filters = Filters.of("category" to "people")).getOrThrow()
            val general = provider.popular(page = 1, filters = Filters.of("category" to "general")).getOrThrow()
            val animeDeep = provider.popular(page = 2, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals("https://www.mordeo.org/wallpapers/category/anime/", client.requests[0])
            assertEquals(5, anime.wallpapers.size)
            assertEquals("https://www.mordeo.org/wallpapers/category/people/", client.requests[1])
            assertEquals(5, people.wallpapers.size)
            // general has no honest expression: the stream serves, the
            // contract's documented behavior for values a provider
            // cannot express.
            assertEquals("https://www.mordeo.org/", client.requests[2])
            assertEquals(5, general.wallpapers.size)
            assertEquals("https://www.mordeo.org/wallpapers/category/anime/page/2/", client.requests[3])
            assertNull(animeDeep.nextPage)
        }

    @Test
    fun sortingRandomServesOnePageWithNoCursor() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.mordeo.org/" to ok(streamFirstPage),
                        "https://www.mordeo.org/page/" to ok(streamLastPage),
                    ),
                )

            val page = provider.popular(page = 1, filters = Filters.of("sorting" to "random")).getOrThrow()

            assertTrue(page.wallpapers.isNotEmpty())
            assertNull(page.nextPage)
            assertTrue(client.requests.single().matches(Regex("""https://www\.mordeo\.org/(page/\d+/)?""")))
        }

    // ------------------------------------------------------------- search

    @Test
    fun searchUsesTheSitesOwnUrlShapes() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.mordeo.org/?s=moana" to ok(streamFirstPage),
                        "https://www.mordeo.org/page/2/?s=moana" to ok(streamLastPage),
                    ),
                )

            val first = provider.search("moana", page = 1).getOrThrow()
            val second = provider.search("moana", page = 2).getOrThrow()

            assertEquals("https://www.mordeo.org/?s=moana", client.requests[0])
            assertEquals(5, first.wallpapers.size)
            assertEquals(2, first.nextPage)
            assertEquals("https://www.mordeo.org/page/2/?s=moana", client.requests[1])
            assertNull(second.nextPage)
        }

    @Test
    fun theEmptySearchPageStaysAnHonestEmptyAnswer() =
        runTest {
            val client = configureWith(mapOf("https://www.mordeo.org/?s=zzzqqqxxx" to ok(emptySearchPage)))

            val page = provider.search("zzzqqqxxx", page = 1).getOrThrow()

            assertEquals("https://www.mordeo.org/?s=zzzqqqxxx", client.requests.single())
            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun blankSearchLandsOnTheStream() =
        runTest {
            val client = configureWith(mapOf("https://www.mordeo.org/" to ok(streamFirstPage)))

            val page = provider.search("  ", page = 1).getOrThrow()

            assertEquals("https://www.mordeo.org/", client.requests.single())
            assertEquals(5, page.wallpapers.size)
        }

    @Test
    fun searchEncodesItsTerms() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.mordeo.org/?s=iron+man" to ok(streamFirstPage),
                    ),
                )

            provider.search("iron man", page = 1).getOrThrow()

            assertEquals("https://www.mordeo.org/?s=iron+man", client.requests.single())
        }

    // --------------------------------------------------------------- tags

    @Test
    fun suggestTagsComeOnlyFromSeenTags() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.mordeo.org/" to ok(streamFirstPage),
                    "https://www.mordeo.org/?s=moana" to ok(streamFirstPage),
                ),
            )

            provider.popular(page = 1).getOrThrow()
            val suggestions = provider.suggestTags("mo").getOrThrow()

            assertTrue(suggestions.isNotEmpty())
            assertTrue(suggestions.any { it == "Moana 2" })
            val blank = provider.suggestTags("").getOrThrow()
            assertTrue(blank.isEmpty())
        }

    @Test
    fun aFreshInstanceSuggestsNothing() =
        runTest {
            val suggestions = provider.suggestTags("mo").getOrThrow()

            assertTrue(suggestions.isEmpty())
        }

    // ----------------------------------------------------------- sections

    @Test
    fun sectionsAnswerTheStreamCategoriesAndQueryPresetsOffline() =
        runTest {
            val client = configureWith(emptyMap())

            val sections = provider.sections()

            assertEquals(14, sections.size)
            assertEquals("popular", sections[0].id)
            assertEquals("Latest", sections[1].title)
            assertEquals(Filters.of("sorting" to "date"), sections[1].filters)
            assertEquals(Filters.of("category" to "anime"), sections[2].filters)
            assertEquals(Filters.of("category" to "people"), sections[3].filters)
            assertTrue(sections.drop(4).all { it.filters.valuesFor("query").isNotEmpty() })
            assertTrue(client.requests.isEmpty())
        }

    // ------------------------------------------------------------ details

    @Test
    fun detailsReadTheNewEraDefinitiveRecord() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.mordeo.org/wallpapers/dwayne-johnson-in-moana-2026-movie/" to ok(detailNewEra),
                    ),
                )

            val details = provider.details("dwayne-johnson-in-moana-2026-movie").getOrThrow()

            assertEquals("https://www.mordeo.org/wallpapers/dwayne-johnson-in-moana-2026-movie/", client.requests.single())
            val wallpaper = details.wallpaper
            assertEquals("dwayne-johnson-in-moana-2026-movie", wallpaper.id)
            assertEquals("Dwayne Johnson In Moana 2026 Movie", wallpaper.title)
            assertEquals(
                "https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-" +
                    "4K-Ultra-HD-Mobile-Wallpaper-950x1689.webp",
                wallpaper.thumbUrl,
            )
            assertEquals(
                "https://www.mordeo.org/files/uploads/2026/07/Dwayne-Johnson-In-Moana-2026-Movie-" +
                    "4K-Ultra-HD-Mobile-Wallpaper.jpg",
                wallpaper.fullUrl,
            )
            assertEquals(2160, wallpaper.width)
            assertEquals(3840, wallpaper.height)
            assertEquals("2160x3840", details.resolution)
            assertEquals(listOf("Dwayne Johnson", "Moana 2"), wallpaper.tags)
            assertEquals(
                "https://www.mordeo.org/wallpapers/dwayne-johnson-in-moana-2026-movie/",
                details.sourceUrl,
            )
        }

    @Test
    fun theMidEraEndpointResolvesToTheStaticOriginal() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.mordeo.org/wallpapers/annabelle-doll-comes-home-2019/" to ok(detailMidEra),
                ),
            )

            val details = provider.details("annabelle-doll-comes-home-2019").getOrThrow()

            // The /download/{id}/ endpoint serves the same bytes as an
            // octet-stream; the derived static original — verified
            // byte-identical live — is preferred.
            assertEquals(
                "https://www.mordeo.org/files/uploads/2019/06/Annabelle-Doll-Comes-Home-2019-" +
                    "4K-Ultra-HD-Mobile-Wallpaper.jpg",
                details.wallpaper.fullUrl,
            )
            assertEquals(2160, details.wallpaper.width)
            assertEquals(3840, details.wallpaper.height)
        }

    @Test
    fun detailsReadTheOldEraPreviewPage() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.mordeo.org/wallpapers/charistmas-candle-light-lamp/" to ok(detailOldEra),
                ),
            )

            val details = provider.details("charistmas-candle-light-lamp").getOrThrow()

            assertEquals(
                "https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper-Preview.jpg",
                details.wallpaper.thumbUrl,
            )
            assertEquals(
                "https://www.mordeo.org/files/uploads/2016/12/Charistmas-Candle-Light-Lamp-Mobile-Wallpaper.jpg",
                details.wallpaper.fullUrl,
            )
            assertEquals("2160x3840", details.resolution)
        }

    @Test
    fun detailsRejectPagesThatAnswerForAnotherSlug() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.mordeo.org/wallpapers/not-the-same-wallpaper/" to ok(detailNewEra),
                ),
            )

            val result = provider.details("not-the-same-wallpaper")

            assertTrue(result.isFailure)
        }

    @Test
    fun detailsFailWhenThePageIsGone() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.mordeo.org/wallpapers/deleted-wallpaper/" to
                        ProviderHttpResponse(404, emptyMap(), ByteArray(0)),
                ),
            )

            val result = provider.details("deleted-wallpaper")

            assertTrue(result.isFailure)
        }

    // ---------------------------------------------------------------- meta

    @Test
    fun theMetaAnswersTheManifest() {
        assertEquals("cloudimage.mordeo", provider.meta.id)
        assertEquals("Mordeo", provider.meta.name)
        assertTrue(provider.meta.description.isNotBlank())
        assertEquals(1, provider.capabilities.count { it == Capability.SEARCH })
        assertTrue(Capability.POPULAR in provider.capabilities)
        assertTrue(Capability.LATEST in provider.capabilities)
        assertTrue(Capability.TAGS in provider.capabilities)
        assertTrue(Capability.FILTERS in provider.capabilities)
    }
}

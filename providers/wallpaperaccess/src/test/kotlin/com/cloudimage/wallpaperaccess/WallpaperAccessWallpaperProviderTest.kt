package com.cloudimage.wallpaperaccess

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
 * WallpaperAccess provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the live site's markup: the navigation
 * menu's category links with their emoji, the album cards shared by the
 * homepage shelf, the category directories and the search results, the
 * album wall's items with their true dimensions and extension-sensitive
 * thumbs, the search page's zero-result apology above its trending
 * suggestions, and the flat streams' batching all stay covered without a
 * network.
 */
class WallpaperAccessWallpaperProviderTest {
    private val provider = WallpaperAccessWallpaperProvider()

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

    // Fixtures: shapes captured from wallpaperaccess.com, trimmed to the
    // parts the parsers key on.

    /** The navigation menu's category links — real markup, web and mobile copies. */
    private val navMenu =
        """
        <div class="ui flowing popup categories_menu_web">
            <div class="ui two column relaxed grid">
                <div class="column">
                    <div class="ui link list">
                        <a href="/cat/abstract" class="item" style="color: black">🌀 Abstract</a>
                        <a href="/cat/aesthetic" class="item" style="color: black">🌻 Aesthetic</a>
                        <a href="/cat/animals" class="item" style="color: black">🐶 Animals</a>
                        <a href="/cat/anime" class="item" style="color: black">💥 Anime</a>
                        <a href="/cat/tv-shows" class="item" style="color: black">📺 TV Shows</a>
                    </div>
                </div>
            </div>
        </div>
        <div class="ui flowing popup categories_menu_mobile">
            <div class="ui link list">
                <a href="/cat/abstract" class="item">🌀 Abstract</a>
                <a href="/cat/anime" class="item">💥 Anime</a>
            </div>
        </div>
        """.trimIndent()

    /** The homepage's collections segment — real card markup, one mobile duplicate. */
    private val homePage =
        navMenu +
            """
            <div id="collections_segment">
                <div class="ui center aligned stackable three column grid container" style="margin-top: 20px">
                    <div class="column collection_thumb">
                <a title="October 2026 Calendar Wallpapers" class="ui fluid image"
                   href="/october-2026-calendar">
                    <img class="preload"
                             alt="October 2026 Calendar Wallpaper"
                             data-src="/thumb/90211.jpg"
                             data-src-retina="/thumb/90211.jpg"
                             src="/thumb/90211.jpg">
                    <div class="flexbox content-center" style="padding-top: 5px;">
                        <div>
                            <span class="ui color_black h">October 2026 Calendar</span>
                            <span class="color_gray" style="font-size: 12px !important; line-height: 1;">&nbsp;
                                <i class="image icon"></i> 12                        </span>
                        </div>
                    </div>
                </a>
                                                                                </div>
                <div class="column collection_thumb">
                <a title="Attack On Titan Wallpapers" class="ui fluid image"
                   href="/attack-on-titan">
                    <img class="preload"
                             alt="Attack On Titan Wallpaper"
                             data-src="/thumb/36626.jpg"
                             data-src-retina="/thumb/36626.jpg"
                             src="/thumb/36626.jpg">
                    <div class="flexbox content-center" style="padding-top: 5px;">
                        <div>
                            <span class="ui color_black h">Attack On Titan</span>
                            <span class="color_gray" style="font-size: 12px !important; line-height: 1;">&nbsp;
                                <i class="image icon"></i> 70                        </span>
                        </div>
                    </div>
                </a>
                                                                                </div>
                <div class="column collection_thumb">
                <a title="One Piece Wallpapers" class="ui fluid image"
                   href="/one-piece">
                    <img class="preload"
                             alt="One Piece Wallpaper"
                             data-src="/thumb/17350.jpg"
                             data-src-retina="/thumb/17350.jpg"
                             src="/thumb/17350.jpg">
                    <div class="flexbox content-center" style="padding-top: 5px;">
                        <div>
                            <span class="ui color_black h">One Piece</span>
                            <span class="color_gray" style="font-size: 12px !important; line-height: 1;">&nbsp;
                                <i class="image icon"></i> 61                        </span>
                        </div>
                    </div>
                </a>
                                                                                </div>
                <div class="column collection_thumb">
                <a title="BTS Wallpapers" class="ui fluid image"
                   href="/bts">
                    <img class="preload"
                             alt="BTS Wallpaper"
                             data-src="/thumb/28123.jpg"
                             data-src-retina="/thumb/28123.jpg"
                             src="/thumb/28123.jpg">
                    <div class="flexbox content-center" style="padding-top: 5px;">
                        <div>
                            <span class="ui color_black h">BTS</span>
                            <span class="color_gray" style="font-size: 12px !important; line-height: 1;">&nbsp;
                                <i class="image icon"></i> 88                        </span>
                        </div>
                    </div>
                </a>
                                                                                </div>
                </div>
            </div>
            """.trimIndent()

    /** A category directory page — the same card markup under /cat/. */
    private val categoryPage =
        navMenu +
            """
            <div id="collections_segment">
                <div class="ui center aligned stackable three column grid container" style="margin-top: 20px">
                    <div class="column collection_thumb">
                <a title="Naruto Wallpapers" class="ui fluid image"
                   href="/naruto">
                    <img class="preload" alt="Naruto Wallpaper" data-src="/thumb/12111.jpg" src="/thumb/12111.jpg">
                    <div class="flexbox content-center" style="padding-top: 5px;">
                        <div>
                            <span class="ui color_black h">Naruto</span>
                            <span class="color_gray">&nbsp; <i class="image icon"></i> 96</span>
                        </div>
                    </div>
                </a>
                </div>
                <div class="column collection_thumb">
                <a title="Naruto Shippuden Wallpapers" class="ui fluid image"
                   href="/naruto-shippuden">
                    <img class="preload" alt="Naruto Shippuden Wallpaper" data-src="/thumb/13131.png" src="/thumb/13131.png">
                    <div class="flexbox content-center" style="padding-top: 5px;">
                        <div>
                            <span class="ui color_black h">Naruto Shippuden</span>
                            <span class="color_gray">&nbsp; <i class="image icon"></i> 54</span>
                        </div>
                    </div>
                </a>
                </div>
                </div>
            </div>
            """.trimIndent()

    /**
     * An album wall — real item markup: a JPG original with
     * fetchpriority and a PNG original behind a lazy preload, exactly the
     * extension pair the thumb endpoint is strict about.
     */
    private val albumPage =
        navMenu +
            """
            <div id="maincontent">
                <div class="flexbox column maincol single_image pad_horisont15">
                    <div id="36626"
                 data-fullimg="/full/36626.jpg"
                 data-or="3840x2160"
                 data-fsb="http://www.facebook.com/sharer.php?u=https://wallpaperaccess.com/attack-on-titan#36626"
                 data-tw="https://twitter.com/share?url=example"
                 data-pin="http://pinterest.com/pin/create/button/?url=example"
                 data-download="
                 /download/attack-on-titan-36626"
                 class="flexbox_item">
                <div class="wrapper">
                    <a href="/download/attack-on-titan-36626">
                                            <img class=" thumb ads_popup"
                             data-id="36626"
                             data-slug="attack-on-titan"
                             alt="3840x2160 Attack On Titan HD Wallpaper and Background Image"
                             src="/full/36626.jpg"
                             fetchpriority="high">
                                            <div class="image_cap">
                            <span class="color_white">3840x2160 Attack On Titan HD Wallpaper and Background Image"></span>
                        </div>
                    </a>
                </div>
            </div>
                    <div id="36627"
                 data-fullimg="/full/36627.png"
                 data-or="1920x1200"
                 data-fsb="http://www.facebook.com/sharer.php?u=example"
                 data-tw="https://twitter.com/share?url=example"
                 data-pin="http://pinterest.com/pin/create/button/?url=example"
                 data-download="
                 /download/attack-on-titan-36627"
                 class="flexbox_item">
                <div class="wrapper">
                    <a href="/download/attack-on-titan-36627">
                                            <img class=" thumb preload ads_popup"
                             data-id="36627"
                             data-slug="attack-on-titan"
                             alt="1920x1200 Attack On Titan HD Wallpaper and Background Image"
                             data-src="/full/36627.png">
                                            <div class="image_cap">
                            <span class="color_white">1920x1200 Attack On Titan HD Wallpaper and Background Image"></span>
                        </div>
                    </a>
                </div>
            </div>
            </div>
            </div>
            """.trimIndent()

    /** The search answer — the same card markup under /search. */
    private val searchPage =
        """
        <h1 class="ui centered aligned">Search result for naruto</h1>
        <div id="collections_segment">
            <div class="ui center aligned stackable three column grid container" style="margin-top: 20px">
                <div class="column collection_thumb">
            <a title="Naruto Wallpapers" class="ui fluid image"
               href="/naruto">
                <img class="preload" alt="Naruto Wallpaper" data-src="/thumb/12111.jpg" src="/thumb/12111.jpg">
                <div class="flexbox content-center" style="padding-top: 5px;">
                    <div>
                        <span class="ui color_black h">Naruto</span>
                        <span class="color_gray">&nbsp; <i class="image icon"></i> 96</span>
                    </div>
                </div>
            </a>
            </div>
            <div class="column collection_thumb">
            <a title="Naruto And Sasuke Wallpapers" class="ui fluid image"
               href="/naruto-and-sasuke">
                <img class="preload" alt="Naruto And Sasuke Wallpaper" data-src="/thumb/14141.jpg" src="/thumb/14141.jpg">
                <div class="flexbox content-center" style="padding-top: 5px;">
                    <div>
                        <span class="ui color_black h">Naruto And Sasuke</span>
                        <span class="color_gray">&nbsp; <i class="image icon"></i> 41</span>
                    </div>
                </div>
            </a>
            </div>
            </div>
        </div>
        """.trimIndent()

    /**
     * The zero-result page — real shape: the apology first, then the
     * homepage's trending cards. The trending cards are the site's
     * suggestions and must never be served as results.
     */
    private val zeroResultsPage =
        """
        <h1 class="ui centered aligned">Search result for zzxxqqwertynope</h1>
        <div id="collections_segment">
            <div class="ui center aligned stackable three column grid container" style="margin-top: 20px">
                <div style="width:100%;text-align:center;margin:40px 0 20px">
                    <p style="color:#555;font-size:1.5em;margin:0">Sorry, no wallpapers found for 'zzxxqqwertynope'.</p>
                </div>
                <p style="width:100%;text-align:center">You can <a href="/request?q=zzxxqqwertynope"><strong>request</strong> some zzxxqqwertynope wallpapers</a> and we'll publish some for you in the next 24 hours!<p>
                <div class="column collection_thumb">
            <a title="4K iPhone Wallpapers" class="ui fluid image"
               href="/4k-iphone">
                <img class="preload" alt="4K iPhone Wallpaper" data-src="/thumb/50101.jpg" src="/thumb/50101.jpg">
                <div class="flexbox content-center" style="padding-top: 5px;">
                    <div>
                        <span class="ui color_black h">4K iPhone</span>
                        <span class="color_gray">&nbsp; <i class="image icon"></i> 142</span>
                    </div>
                </div>
            </a>
            </div>
            </div>
        </div>
        """.trimIndent()

    // ------------------------------------------------------------- albums

    @Test
    fun categoriesAnswerTheBakedListOffline() =
        runTest {
            // Nothing routes — the live refresh fails and the offline
            // floor stands: the baked navigation keeps the sidebar alive.
            configureWith(emptyMap())

            val categories = provider.categories()

            assertEquals(28, categories.size)
            val anime = categories.first { it.id == "anime" }
            assertEquals("Anime", anime.name)
            assertEquals("💥", anime.iconEmoji)
            val tvShows = categories.first { it.id == "tv-shows" }
            assertEquals("TV Shows", tvShows.name)
            assertEquals("📺", tvShows.iconEmoji)
        }

    @Test
    fun categoriesHarvestTheLiveNavigationFromFetchedPages() =
        runTest {
            configureWith(mapOf("https://wallpaperaccess.com/" to ok(homePage)))

            provider.homeAlbums().getOrThrow()
            val categories = provider.categories()

            // The live menu replaces the baked copy, deduped across the
            // web and mobile renditions.
            assertEquals(listOf("abstract", "aesthetic", "animals", "anime", "tv-shows"), categories.map { it.id })
            assertEquals("🌀", categories.first { it.id == "abstract" }.iconEmoji)
            assertEquals("TV Shows", categories.first { it.id == "tv-shows" }.name)
        }

    @Test
    fun homeAlbumsServeTheHomepageShelf() =
        runTest {
            val client = configureWith(mapOf("https://wallpaperaccess.com/" to ok(homePage)))

            val albums = provider.homeAlbums().getOrThrow()

            assertEquals(
                listOf("october-2026-calendar", "attack-on-titan", "one-piece", "bts"),
                albums.map { it.id },
            )
            val attack = albums.first { it.id == "attack-on-titan" }
            assertEquals("Attack On Titan", attack.title)
            assertEquals("https://wallpaperaccess.com/thumb/36626.jpg", attack.coverUrl)
            assertEquals(70, attack.wallpaperCount)
            assertEquals("cloudimage.wallpaperaccess", attack.providerId)
            assertEquals(listOf("https://wallpaperaccess.com/"), client.requests)
        }

    @Test
    fun albumsServeTheCategoryDirectoryComplete() =
        runTest {
            configureWith(mapOf("https://wallpaperaccess.com/cat/naruto" to ok(categoryPage)))

            val albums = provider.albums("naruto").getOrThrow()

            assertEquals(listOf("naruto", "naruto-shippuden"), albums.map { it.id })
            assertEquals("Naruto Shippuden", albums[1].title)
            assertEquals("https://wallpaperaccess.com/thumb/13131.png", albums[1].coverUrl)
            assertEquals(54, albums[1].wallpaperCount)
        }

    @Test
    fun albumWallpapersServeTheCompleteWallWithTrueDimensionsAndExtensionSafeThumbs() =
        runTest {
            configureWith(mapOf("https://wallpaperaccess.com/attack-on-titan" to ok(albumPage)))

            val wallpapers = provider.albumWallpapers("attack-on-titan").getOrThrow()

            assertEquals(2, wallpapers.size)
            val jpg = wallpapers.first { it.id == "attack-on-titan/36626.jpg" }
            assertEquals("https://wallpaperaccess.com/thumb/36626.jpg", jpg.thumbUrl)
            assertEquals("https://wallpaperaccess.com/full/36626.jpg", jpg.fullUrl)
            assertEquals(3840, jpg.width)
            assertEquals(2160, jpg.height)
            assertEquals("Attack On Titan", jpg.title)
            // The PNG original keeps its extension in the thumb — the
            // endpoint answers 415 for a mismatched one.
            val png = wallpapers.first { it.id == "attack-on-titan/36627.png" }
            assertEquals("https://wallpaperaccess.com/thumb/36627.png", png.thumbUrl)
            assertEquals("https://wallpaperaccess.com/full/36627.png", png.fullUrl)
            assertEquals(1920, png.width)
            assertEquals(1200, png.height)
        }

    @Test
    fun searchAlbumsServeTheSitesOwnAnswerAndTheZeroResultApologyStaysEmpty() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpaperaccess.com/search?q=naruto" to ok(searchPage),
                        "https://wallpaperaccess.com/search?q=zzxx" to ok(zeroResultsPage),
                    ),
                )

            val found = provider.searchAlbums("naruto").getOrThrow()
            assertEquals(listOf("naruto", "naruto-and-sasuke"), found.map { it.id })

            val none = provider.searchAlbums("zzxxqqwertynope").getOrThrow()
            assertTrue(none.isEmpty())
            // The trending card behind the apology was never served.
            assertTrue(none.none { it.id == "4k-iphone" })
            assertEquals(
                listOf(
                    "https://wallpaperaccess.com/search?q=naruto",
                    "https://wallpaperaccess.com/search?q=zzxxqqwertynope",
                ),
                client.requests,
            )
        }

    @Test
    fun blankSearchAlbumsAnswerTheHomepageShelf() =
        runTest {
            configureWith(mapOf("https://wallpaperaccess.com/" to ok(homePage)))

            val albums = provider.searchAlbums("").getOrThrow()

            assertEquals(listOf("october-2026-calendar", "attack-on-titan", "one-piece", "bts"), albums.map { it.id })
        }

    // -------------------------------------------------------- flat feeds

    @Test
    fun popularWalksTheNewestAlbumsThreeAtATime() =
        runTest {
            // Specific routes first: the router matches by prefix in
            // insertion order, and the bare site root would shadow them.
            val client =
                configureWith(
                    mapOf(
                        "https://wallpaperaccess.com/october-2026-calendar" to ok(albumPage),
                        "https://wallpaperaccess.com/attack-on-titan" to ok(albumPage),
                        "https://wallpaperaccess.com/one-piece" to ok(albumPage),
                        "https://wallpaperaccess.com/bts" to ok(albumPage),
                        "https://wallpaperaccess.com/" to ok(homePage),
                    ),
                )

            val page1 = provider.popular(page = 1).getOrThrow()
            assertEquals(6, page1.wallpapers.size) // three albums, two walls each
            assertTrue(page1.hasNext)
            assertEquals(2, page1.nextPage)
            // Dedupe: the same fixture serves every album, the ids differ only by slug prefix.
            assertEquals(6, page1.wallpapers.distinctBy { it.id }.size)

            val page2 = provider.popular(page = 2).getOrThrow()
            assertEquals(2, page2.wallpapers.size) // the shelf's last album
            assertNull(page2.nextPage)

            val page3 = provider.popular(page = 3).getOrThrow()
            assertTrue(page3.wallpapers.isEmpty())
            assertNull(page3.nextPage)
            // The shelf was fetched once; only the album pages walked.
            assertEquals(1, client.requests.count { it == "https://wallpaperaccess.com/" })
        }

    @Test
    fun searchFlattensMatchingAlbumsAndTheZeroResultApologyStaysEmpty() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpaperaccess.com/search?q=naruto" to ok(searchPage),
                    "https://wallpaperaccess.com/naruto" to ok(albumPage),
                    "https://wallpaperaccess.com/naruto-and-sasuke" to ok(albumPage),
                    "https://wallpaperaccess.com/search?q=zzxx" to ok(zeroResultsPage),
                ),
            )

            val page1 = provider.search("naruto", page = 1).getOrThrow()
            assertEquals(4, page1.wallpapers.size)
            assertNull(page1.nextPage) // both matching albums fit one batch

            val none = provider.search("zzxxqqwertynope", page = 1).getOrThrow()
            assertTrue(none.wallpapers.isEmpty())
            assertNull(none.nextPage)
        }

    @Test
    fun blankSearchLandsOnTheHomepageShelf() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpaperaccess.com/october-2026-calendar" to ok(albumPage),
                    "https://wallpaperaccess.com/attack-on-titan" to ok(albumPage),
                    "https://wallpaperaccess.com/one-piece" to ok(albumPage),
                    "https://wallpaperaccess.com/" to ok(homePage),
                ),
            )

            val page = provider.search("", page = 1).getOrThrow()

            assertEquals(6, page.wallpapers.size)
        }

    @Test
    fun detailsReReadsTheAlbumRecord() =
        runTest {
            configureWith(mapOf("https://wallpaperaccess.com/attack-on-titan" to ok(albumPage)))

            val details = provider.details("attack-on-titan/36626.jpg").getOrThrow()

            assertEquals("3840x2160", details.resolution)
            assertEquals("Attack On Titan", details.wallpaper.title)
            assertEquals("https://wallpaperaccess.com/full/36626.jpg", details.wallpaper.fullUrl)
            assertEquals("https://wallpaperaccess.com/attack-on-titan#36626", details.sourceUrl)
        }

    @Test
    fun detailsRejectIdsTheProviderNeverMinted() =
        runTest {
            configureWith(emptyMap())

            val result = provider.details("36626")

            assertTrue(result.isFailure)
        }

    @Test
    fun suggestTagsComeOnlyFromSeenTitles() =
        runTest {
            configureWith(mapOf("https://wallpaperaccess.com/" to ok(homePage)))

            // A fresh instance answers nothing.
            assertTrue(provider.suggestTags("titan").getOrThrow().isEmpty())

            provider.homeAlbums().getOrThrow()
            val suggestions = provider.suggestTags("titan").getOrThrow()

            assertEquals(listOf("Attack On Titan"), suggestions)
        }

    @Test
    fun capabilitiesDeclareTheAlbumParadigm() {
        assertTrue(Capability.ALBUMS in provider.capabilities)
        assertTrue(Capability.POPULAR in provider.capabilities)
        assertTrue(Capability.SEARCH in provider.capabilities)
        assertTrue(Capability.FILTERS !in provider.capabilities)
        assertEquals("cloudimage.wallpaperaccess", provider.meta.id)
    }
}

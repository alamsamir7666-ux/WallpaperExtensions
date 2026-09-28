package com.cloudimage.wallpapers4k

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4K Wallpapers provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the live site's markup: the shared
 * schema.org listing grid (homepage, popular, category, search), the
 * pagination bar's ctrl-right contract, the search pager's hidden
 * load-more walk (page-one form, paged path form, last-page stop,
 * zero-result and 404 honesty), the detail record's labeled original and
 * true dimensions, and the host-vocabulary routing all stay covered
 * without a network.
 */
class Wallpapers4KWallpaperProviderTest {
    private val provider = Wallpapers4KWallpaperProvider()

    /**
     * URL-routed responses; unmatched URLs answer 500 to fail loudly.
     * Routes are checked in insertion order, so listing-specific keys must
     * be registered before the homepage's bare `/` prefix.
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

    // Fixtures: shapes captured from 4kwallpapers.com, trimmed to the parts
    // the parsers key on.

    /** The popular feed's first cells — real markup, full schema.org shape. */
    private val popularGrid =
        """
        <div class="pics" id="pics-list"><p itemprop="associatedMedia" itemscope itemtype="http://schema.org/ImageObject" class="wallpapers__item" ><meta itemprop="keywords" content="Xiaomi 18 Fold, Stock, Abstract art, 3D Render, Glass, Dark background"> <link itemprop="contentUrl" href="/images/walls/thumbs_2t/27263.jpg">
                                        <a title="Xiaomi 18 Fold Wallpaper" itemprop="url" data-ripples class="wallpapers__canvas_image"  href="/abstract/xiaomi-18-fold-27263.html">
                                        <span class="wallpapers__canvas ripple">
                                        <img itemprop="thumbnail" src="/images/walls/thumbs/27263.jpg" fetchpriority="high"  srcset="/images/walls/thumbs/27263.jpg 400w,/images/walls/thumbs_2t/27263.jpg 800w" sizes="(min-width: 1400px) 323px" width="400" height="225" alt="Xiaomi 18 Fold, Stock, Abstract art, 3D Render, Glass, Dark background"/>
                                        </span></a>
                                        <span itemprop="caption description" class="title2"><a href="/stock" title="Stock Wallpapers">Stock</a>, <a href="/abstract-art" title="Abstract art Wallpapers">Abstract art</a></span>
                                        </p><p itemprop="associatedMedia" itemscope itemtype="http://schema.org/ImageObject" class="wallpapers__item" ><meta itemprop="keywords" content="Moon, Dark background, Space, NASA, 5K"> <link itemprop="contentUrl" href="/images/walls/thumbs_2t/26344.png">
                                        <a title="Moon Wallpaper" itemprop="url" data-ripples class="wallpapers__canvas_image"  href="/space/moon-dark-26344.html">
                                        <span class="wallpapers__canvas ripple">
                                        <img itemprop="thumbnail" src="/images/walls/thumbs/26344.png"   srcset="/images/walls/thumbs/26344.png 400w,/images/walls/thumbs_2t/26344.png 800w" sizes="(min-width: 1400px) 323px" width="400" height="225" alt="Moon, Dark background, Space, NASA, 5K"/>
                                        </span></a>
                                        <span itemprop="caption description" class="title2"><a href="/moon" title="Moon Wallpapers">Moon</a></span>
                                        </p>
        """.trimIndent()

    /** The pagination bar — Next is the anchor carrying `ctrl-right`. */
    private val popularPagination =
        """
        <p class="pages"><strong class="active" data-page="1">1</strong> <a  data-ripples href="?page=2">2</a> <a  data-ripples href="?page=3">3</a> <span>&hellip;</span> <a data-ripples href="?page=100">100</a>  <a data-ripples href="?page=2" class="ctrl-right">Next &rsaquo;</a></p>
        """.trimIndent()

    /** The last page's bar — Previous only, no ctrl-right. */
    private val lastPagePagination =
        """
        <p class="pages"><a data-ripples href="?page=13" class="ctrl-left">&lsaquo; Previous</a> <strong class="active" data-page="14">14</strong></p>
        """.trimIndent()

    /** The search results grid — cells plus the HIDDEN, script-driven pager bar. */
    private val searchGrid =
        """
        <div class="pics" id="pics-list"><p itemprop="associatedMedia" itemscope itemtype="http://schema.org/ImageObject" class="wallpapers__item" ><meta itemprop="keywords" content="Naruto Uzumaki, Anime series, Manga, 4K"> <link itemprop="contentUrl" href="/images/walls/thumbs_2t/27165.jpg">
                                        <a title="Naruto Uzumaki Wallpaper" itemprop="url" data-ripples class="wallpapers__canvas_image"  href="/anime/naruto-uzumaki-27165.html">
                                        <span class="wallpapers__canvas ripple">
                                        <img itemprop="thumbnail" src="/images/walls/thumbs/27165.jpg" loading="lazy" width="400" height="225" alt="Naruto Uzumaki, Anime series, Manga, 4K"/>
                                        </span></a>
                                        </p>
        """.trimIndent()

    /**
     * The hidden search pager, cut from the live site's `indian actress`
     * results — `<strong>` markers instead of anchors, the load-more
     * script's own source of truth. Cut exactly as served, hidden style
     * and all.
     */
    private val searchPager =
        """
        <p class="pages" style="display: none;"><strong class="active" data-page="1">1</strong> <strong data-page="2">2</strong> <strong data-page="3">3</strong> <span>&hellip;</span> <strong data-page="18">18</strong>  <strong class="ctrl-right">Next &rsaquo;</strong></p>
        """.trimIndent()

    /** A deeper page's hidden pager — the active marker has moved. */
    private val searchPagerPageTwo =
        """
        <p class="pages" style="display: none;"><strong data-page="1">1</strong> <strong class="active" data-page="2">2</strong> <strong data-page="3">3</strong> <strong data-page="4">4</strong> <span>&hellip;</span> <strong data-page="18">18</strong>  <strong class="ctrl-right">Next &rsaquo;</strong></p>
        """.trimIndent()

    /** The last page's hidden pager — active IS the last marker: the walk ends. */
    private val searchPagerLast =
        """
        <p class="pages" style="display: none;"><strong data-page="1">1</strong> <span>&hellip;</span> <strong data-page="17">17</strong> <strong class="active" data-page="18">18</strong></p>
        """.trimIndent()

    /**
     * A wallpaper page — the page-level keywords meta, the contentUrl
     * preview image, the resolution menu with its labeled original, the
     * category/tag rows, and one lean related cell (no meta, no link —
     * its img alt carries the title and tags instead).
     */
    private val detailPage =
        """
        <meta itemprop="keywords" content="Xiaomi 18 Fold, Stock, Abstract art, 3D Render, Glass, Dark background">
        <h1>Xiaomi 18 Fold Stock Wallpaper</h1>
        <img itemprop="contentUrl" fetchpriority="high" src="/images/walls/thumbs_2t/27263.jpg" srcset="/images/walls/thumbs_2t/27263.jpg 800w,/images/walls/thumbs_3t/27263.jpg 1280w" width="800" height="450"/>
        <div class="pic-right"><div><span class="res-ttl">
                <a title="Download Xiaomi 18 Fold Stock 1920x1080 wallpaper" href="/images/wallpapers/xiaomi-18-fold-1920x1080-27263.jpg" target="_blank">1920x1080 <span id="info">(Full HD 1080p)</span></a>
                <a title="Download Xiaomi 18 Fold Stock 4K Wallpaper" href="/images/wallpapers/xiaomi-18-fold-3840x2160-27263.jpg" class="current" id="resolution" target="_blank"><i class="fas fa-download"></i> Download in 4K (3840x2160) </a>
                <a title="Download Xiaomi 18 Fold Stock Original Wallpaper" href="/images/wallpapers/xiaomi-18-fold-5120x2880-27263.jpg" class="current" id="resolution" target="_blank"><i class="fas fa-download"></i> Download Original (5120x2880)</a>
                </span><p class="tags"><span class="right-tags">Categories</span><a href="/abstract/" title="Abstract Wallpapers">Abstract</a>&nbsp;</p><p class="tags"><span class="right-tags">Tags</span><a href="/stock" title="Stock Wallpapers">Stock</a> <a href="/abstract-art" title="Abstract art Wallpapers">Abstract art</a></p></div></div>
                <div class="pics related" id="pics-list"><h2 class="title-3">Related Wallpapers</h2><p class="wallpapers__item">
                                        <a title="Microsoft Surface Wallpaper" data-ripples class="wallpapers__canvas_image" href="/abstract/microsoft-surface-26737.html">
                                        <span class="wallpapers__canvas ripple">
                                                <img src="/images/walls/thumbs/26737.jpg" loading="lazy" srcset="/images/walls/thumbs/26737.jpg 400w, /images/walls/thumbs_2t/26737.jpg 800w" width="400" height="225" alt="Microsoft Surface, Stock, 5K, Black background" class="hor" />
                                        </span></a>
                                        <span class="title2"><a href="/microsoft-surface" title="Microsoft Surface Wallpapers">Microsoft Surface</a></span>
                                        </p></div>
        """.trimIndent()

    // ------------------------------------------------------------ the feeds

    @Test
    fun `popular serves the ranking's mapped grid items`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/most-popular-4k-wallpapers/" to ok(popularGrid + popularPagination),
                    ),
                )

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals("https://4kwallpapers.com/most-popular-4k-wallpapers/", client.requests.single())
            assertEquals(2, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertEquals("abstract/xiaomi-18-fold-27263.html", first.id)
            assertEquals("cloudimage.wallpapers4k", first.providerId)
            assertEquals("https://4kwallpapers.com/images/walls/thumbs_2t/27263.jpg", first.thumbUrl)
            assertEquals("https://4kwallpapers.com/images/walls/orig/27263.jpg", first.fullUrl)
            assertEquals("Xiaomi 18 Fold", first.title)
            assertTrue(first.tags.contains("Stock"))
            assertTrue(first.tags.contains("Dark background"))
            // Regression guard for the uniform card-crop trap: the listing
            // publishes 400x225 on EVERY cell, true of no wallpaper, so the
            // grid must carry no dimensions — details()'s alone are true.
            assertNull(first.width)
            assertNull(first.height)
        }

    @Test
    fun `a png cell keeps its extension into the disclosed original`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://4kwallpapers.com/most-popular-4k-wallpapers/" to ok(popularGrid + popularPagination),
                ),
            )

            val png = provider.popular(page = 1).getOrThrow().wallpapers[1]

            assertEquals("space/moon-dark-26344.html", png.id)
            assertEquals("https://4kwallpapers.com/images/walls/orig/26344.png", png.fullUrl)
            assertEquals("https://4kwallpapers.com/images/walls/thumbs_2t/26344.png", png.thumbUrl)
        }

    @Test
    fun `pagination follows the ctrl-right next link`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://4kwallpapers.com/most-popular-4k-wallpapers/" to ok(popularGrid + popularPagination),
                ),
            )

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(2, page.nextPage)
            assertTrue(page.hasNext)
        }

    @Test
    fun `the last page's bar has no ctrl-right and stops the feed`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://4kwallpapers.com/most-popular-4k-wallpapers/" to ok(popularGrid + lastPagePagination),
                ),
            )

            val page = provider.popular(page = 1).getOrThrow()

            assertNull(page.nextPage)
        }

    @Test
    fun `page two walks the query pagination`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/most-popular-4k-wallpapers/" to ok(popularGrid + popularPagination),
                    ),
                )

            provider.popular(page = 2).getOrThrow()

            assertEquals("https://4kwallpapers.com/most-popular-4k-wallpapers/?page=2", client.requests.single())
        }

    @Test
    fun `host category filters walk the site's own category listings`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/anime" to ok(popularGrid + popularPagination),
                        "https://4kwallpapers.com/people" to ok(popularGrid + popularPagination),
                    ),
                )

            val anime = provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            val people = provider.popular(page = 2, filters = Filters.of("category" to "people")).getOrThrow()

            assertEquals("https://4kwallpapers.com/anime", client.requests[0])
            assertEquals("https://4kwallpapers.com/people?page=2", client.requests[1])
            assertTrue(anime.wallpapers.isNotEmpty())
            assertTrue(people.wallpapers.isNotEmpty())
        }

    @Test
    fun `sorting date rides the homepage's freshest grid`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/most-popular-4k-wallpapers/" to ok(popularGrid),
                        "https://4kwallpapers.com/" to ok(popularGrid + popularPagination),
                    ),
                )

            provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()

            assertEquals("https://4kwallpapers.com/", client.requests.single())
        }

    @Test
    fun `deep pagination is refused before any request`() =
        runTest {
            val client = configureWith(emptyMap())

            val page = provider.popular(page = 101).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue("no request may leave for a capped page", client.requests.isEmpty())
        }

    @Test
    fun `search page one rides the form's own address and the hidden pager`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/search/" to ok(searchGrid + searchPager),
                    ),
                )

            val page = provider.search(query = "naruto", page = 1).getOrThrow()

            assertEquals("https://4kwallpapers.com/search/?q=naruto", client.requests.single())
            assertEquals(1, page.wallpapers.size)
            assertEquals("anime/naruto-uzumaki-27165.html", page.wallpapers.first().id)
            assertEquals("the hidden pager offers the load-more walk", 2, page.nextPage)
        }

    @Test
    fun `search page two rides the load-more path form the site's button requests`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/search/naruto?page=2" to ok(searchGrid + searchPagerPageTwo),
                    ),
                )

            val page = provider.search(query = "naruto", page = 2).getOrThrow()

            assertEquals("https://4kwallpapers.com/search/naruto?page=2", client.requests.single())
            assertEquals(1, page.wallpapers.size)
            assertEquals("the active marker has moved with the page", 3, page.nextPage)
        }

    @Test
    fun `a multi-word query page one travels percent-twenty in the q parameter too`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/search/" to ok(searchGrid + searchPager),
                    ),
                )

            provider.search(query = "indian actress", page = 1).getOrThrow()

            // The site reads ?q= as a raw token: the `+` form encoding
            // serves ZERO multi-word results (verified live).
            assertEquals("https://4kwallpapers.com/search/?q=indian%20actress", client.requests.single())
        }

    @Test
    fun `a multi-word query travels the path as percent-twenty, never plus`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/search/indian%20actress?page=2" to ok(searchGrid + searchPagerPageTwo),
                    ),
                )

            provider.search(query = "indian actress", page = 2).getOrThrow()

            // The `+` form is a form convention that a path reads literally
            // — the site answers it 404 (verified live).
            assertEquals("https://4kwallpapers.com/search/indian%20actress?page=2", client.requests.single())
        }

    @Test
    fun `the last search page's pager stops the walk`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://4kwallpapers.com/search/naruto?page=18" to ok(searchGrid + searchPagerLast),
                ),
            )

            val page = provider.search(query = "naruto", page = 18).getOrThrow()

            assertEquals(1, page.wallpapers.size)
            assertNull("active IS the last marker — nothing more", page.nextPage)
        }

    @Test
    fun `a zero-result search has no pager and answers honestly empty-ended`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/search/" to ok("<div class=\"pics\" id=\"pics-list\"></div>"),
                    ),
                )

            val page = provider.search(query = "zzxxqqyywerty", page = 1).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertEquals(1, client.requests.size)
        }

    @Test
    fun `a search 404 degrades to an honest empty page, not a failure`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://4kwallpapers.com/search/" to ProviderHttpResponse(404, emptyMap(), ByteArray(0)),
                ),
            )

            val page = provider.search(query = "gone", page = 2).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun `search past the deep cap refuses before any request`() =
        runTest {
            val client = configureWith(emptyMap())

            val page = provider.search(query = "naruto", page = 101).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue("no request may leave for a capped page", client.requests.isEmpty())
        }

    @Test
    fun `a blank query lands on the trending first page`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/" to ok(popularGrid),
                    ),
                )

            val page = provider.search(query = "  ", page = 1).getOrThrow()

            assertEquals("https://4kwallpapers.com/", client.requests.single())
            assertTrue(page.wallpapers.isNotEmpty())
        }

    // -------------------------------------------------------------- details

    @Test
    fun `details reads the labeled original and the true dimensions`() =
        runTest {
            val id = "abstract/xiaomi-18-fold-27263.html"
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/abstract/xiaomi-18-fold-27263.html" to ok(detailPage),
                    ),
                )

            val details = provider.details(id).getOrThrow()

            assertEquals("https://4kwallpapers.com/abstract/xiaomi-18-fold-27263.html", client.requests.single())
            assertEquals(id, details.wallpaper.id)
            assertEquals("https://4kwallpapers.com/images/wallpapers/xiaomi-18-fold-5120x2880-27263.jpg", details.wallpaper.fullUrl)
            assertEquals("https://4kwallpapers.com/images/walls/thumbs_2t/27263.jpg", details.wallpaper.thumbUrl)
            assertEquals(5120, details.wallpaper.width)
            assertEquals(2880, details.wallpaper.height)
            assertEquals("5120x2880", details.resolution)
            assertEquals("Xiaomi 18 Fold", details.wallpaper.title)
            assertTrue(details.wallpaper.tags.contains("Stock"))
            assertEquals("https://4kwallpapers.com/abstract/xiaomi-18-fold-27263.html", details.sourceUrl)
            // The site publishes neither — honest nulls, never inventions.
            assertNull(details.author)
            assertNull(details.fileSizeBytes)
        }

    @Test
    fun `details falls back to the orig directory when the labeled anchor is gone`() =
        runTest {
            val stripped =
                detailPage
                    .replace(
                        Regex("""<a[^>]*>\s*<i[^>]*></i>\s*Download Original[^<]*</a>"""),
                        "",
                    )
            configureWith(
                linkedMapOf(
                    "https://4kwallpapers.com/abstract/xiaomi-18-fold-27263.html" to ok(stripped),
                ),
            )

            val details = provider.details("abstract/xiaomi-18-fold-27263.html").getOrThrow()

            assertEquals("https://4kwallpapers.com/images/walls/orig/27263.jpg", details.wallpaper.fullUrl)
            assertNull(details.resolution)
            assertNull(details.wallpaper.width)
            assertNull(details.wallpaper.height)
        }

    @Test
    fun `details propagates http failures as source errors`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://4kwallpapers.com/abstract/xiaomi-18-fold-27263.html" to
                        ProviderHttpResponse(500, emptyMap(), ByteArray(0)),
                ),
            )

            val result = provider.details("abstract/xiaomi-18-fold-27263.html")

            assertTrue(result.isFailure)
            assertEquals("4kwallpapers answered HTTP 500", result.exceptionOrNull()!!.message)
        }

    // ------------------------------------------------- sections, tags, random

    @Test
    fun `sections ride the host vocabulary across fourteen shelves`() =
        runTest {
            val sections = provider.sections()

            assertEquals(14, sections.size)
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

    @Test
    fun `suggestTags answers from tags already seen, never from requests`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/most-popular-4k-wallpapers/" to ok(popularGrid + popularPagination),
                    ),
                )
            provider.popular(page = 1).getOrThrow()

            assertEquals(listOf("stock"), provider.suggestTags("sto").getOrThrow())
            assertTrue(provider.suggestTags("stock").getOrThrow().contains("stock"))
            assertTrue(provider.suggestTags("zzz").getOrThrow().isEmpty())
            assertTrue(provider.suggestTags("  ").getOrThrow().isEmpty())
            assertEquals(1, client.requests.size)
        }

    @Test
    fun `random serves the freshest batch the site itself shows`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://4kwallpapers.com/" to ok(popularGrid),
                    ),
                )

            val wallpapers = provider.random().getOrThrow()

            assertEquals("https://4kwallpapers.com/", client.requests.single())
            assertEquals(2, wallpapers.size)
        }

    // ------------------------------------------------------- parser details

    @Test
    fun `a malformed cell drops without swallowing its neighbor`() {
        val grid =
            """
            <p class="wallpapers__item"><span class="wallpapers__canvas ripple"></span></p>
            <p itemprop="associatedMedia" itemscope itemtype="http://schema.org/ImageObject" class="wallpapers__item" ><meta itemprop="keywords" content="Moon, Dark background"> <link itemprop="contentUrl" href="/images/walls/thumbs_2t/26344.png">
                                        <a title="Moon Wallpaper" itemprop="url" data-ripples class="wallpapers__canvas_image"  href="/space/moon-dark-26344.html">
                                        <img itemprop="thumbnail" src="/images/walls/thumbs/26344.png" width="400" height="225" alt="Moon, Dark background"/>
                                        </a></p>
            """.trimIndent()

        val items = Wallpapers4KParser.parseGrid(grid)

        assertEquals(1, items.size)
        assertEquals("space/moon-dark-26344.html", items.single().id)
    }

    @Test
    fun `lean related cells parse from the alt text`() {
        val items = Wallpapers4KParser.parseGrid(detailPage)

        // The fixture's full cells are the detail page's related ones: no
        // keywords meta, no contentUrl link — the img's alt and src carry
        // everything.
        val related = items.single()
        assertEquals("abstract/microsoft-surface-26737.html", related.id)
        assertEquals("Microsoft Surface", related.title)
        assertTrue(related.tags.contains("Stock"))
        assertTrue(related.tags.contains("5K"))
        assertEquals("https://4kwallpapers.com/images/walls/thumbs/26737.jpg", related.thumbUrl)
        assertEquals("https://4kwallpapers.com/images/walls/orig/26737.jpg", related.originalUrl)
    }

    @Test
    fun `the search pager reads its markers in any attribute order`() {
        // data-page BEFORE class, single quotes, an extra class token — the
        // marker still has to be found and read.
        val bar =
            """
            <p class='pages' style='display: none;'><strong data-page='7' class='marker active'>7</strong> <strong data-page='8'>8</strong> <strong class='ctrl-right'>Next &rsaquo;</strong></p>
            """.trimIndent()

        assertEquals(8, Wallpapers4KParser.parseSearchNextPage(bar))
    }

    @Test
    fun `a search pager without an active marker stops honestly`() {
        val bar =
            """
            <p class="pages"><strong data-page="1">1</strong> <strong data-page="2">2</strong></p>
            """.trimIndent()

        assertNull(Wallpapers4KParser.parseSearchNextPage(bar))
        assertNull(Wallpapers4KParser.parseSearchNextPage("<div>no pager at all</div>"))
    }
}

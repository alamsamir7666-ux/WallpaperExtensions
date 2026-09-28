package com.cloudimage.wallpaperaccess

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WallpaperAccess provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the live site's markup: the shared
 * data-attribute listing grid (popular, fresh, collections, slug-guessed
 * search), the related-band walk the two ranked mixed feeds ride, the
 * same-theme sitemap walk every themed root rides (the Nature tab stays
 * nature — never the site's mixed Related band), the robots-compliant
 * slug-guess search with its 404-is-a-miss contract, the detail record
 * re-walked from the id's own listing, and the host-vocabulary routing
 * all stay covered without a network.
 *
 * Walk tests drive FRESH provider instances — the walk caches are
 * instance state, and a test must never depend on the caches another
 * test left behind.
 */
class WallpaperAccessWallpaperProviderTest {
    private val provider = WallpaperAccessWallpaperProvider()

    /**
     * URL-routed responses; unmatched URLs answer 500 to fail loudly.
     * Routes are checked in insertion order, so listing-specific keys must
     * be registered before shorter prefixes.
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

    /**
     * A fresh provider over its own client — for the walk tests, whose
     * related-band cache must start empty every time.
     */
    private fun freshProvider(routes: Map<String, ProviderHttpResponse>): Pair<WallpaperAccessWallpaperProvider, FakeClient> {
        val fresh = WallpaperAccessWallpaperProvider()
        val client =
            FakeClient().apply {
                this.routes = routes
                fresh.configure(this, ProviderSettings { null })
            }
        return fresh to client
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

    /**
     * The Related Wallpapers band a listing page serves after its grid —
     * the real anchor shape from the live site, one card per sibling
     * collection.
     */
    private fun relatedBand(vararg slugs: String): String =
        buildString {
            append("<h2 id=\"related\" class=\"ui center aligned color_black _h2\">Related Wallpapers</h2>\n")
            slugs.forEach { slug ->
                append(
                    "<a title=\"${slug.replaceFirstChar { it.uppercase() }} Wallpapers\" " +
                        "class=\"ui fluid image\" href=\"/$slug\">" +
                        "<img class=\"preload\" alt=\"$slug Wallpaper\" data-src=\"/thumb/1.jpg\"></a>\n",
                )
            }
            append("<h2 class=\"ui center aligned color_black _h2\">How to change your wallpaper</h2>")
        }

    /**
     * The site's public sitemap — the real shape: one `<url>` per
     * collection, the address in `<loc>`, a rolling window of the newest
     * collections (the classic roots are not in it; their siblings are).
     */
    private fun sitemapXml(vararg slugs: String): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
            slugs.forEach { slug ->
                append("<url><loc>https://wallpaperaccess.com/$slug</loc>")
                append("<lastmod>2026-09-26T22:28:01+00:00</lastmod></url>")
            }
            append("</urlset>")
        }

    /** A one-cell listing for a walked collection — the shared cell shape, its own ids. */
    private fun walkedGrid(
        slug: String,
        numericId: String,
    ): String =
        """
        <div id="$numericId"
             data-fullimg="/full/$numericId.jpg"
             data-or="1920x1080"
             data-download="
             /download/$slug-$numericId"
             class="flexbox_item">
            <div class="wrapper">
                <a href="/download/$slug-$numericId">
                    <img class=" thumb preload ads_popup"
                         data-id="$numericId"
                         data-slug="$slug"
                         alt="1920x1080 $slug Cell"
                         data-src="/full/$numericId.jpg">
                </a>
            </div>
        </div>
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
            // The fixture carries no related band — the feed ends after
            // its batch. The walk tests below cover the banded shape.
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

    // -------------------------------------------------------- the endless walk

    @Test
    fun `page one seeds the walk and deeper pages ride the related band`() =
        runTest {
            val (fresh, client) =
                freshProvider(
                    linkedMapOf(
                        "https://wallpaperaccess.com/most-popular" to ok(popularGrid + relatedBand("flowers", "stars")),
                        "https://wallpaperaccess.com/flowers" to ok(walkedGrid("flowers", "900001")),
                        "https://wallpaperaccess.com/stars" to ok(walkedGrid("stars", "900002")),
                    ),
                )

            val first = fresh.popular(page = 1).getOrThrow()
            assertEquals("the band has cards, so the walk continues", 2, first.nextPage)

            val second = fresh.popular(page = 2).getOrThrow()
            assertEquals(1, second.wallpapers.size)
            // The WALKED collection's slug rides in the id — the re-fetch
            // address details() will use.
            val walked = second.wallpapers.single()
            assertEquals("flowers/900001.jpg", walked.id)
            assertEquals("flowers", walked.tags.single())
            assertEquals("still one card left in the band", 3, second.nextPage)

            val third = fresh.popular(page = 3).getOrThrow()
            assertEquals("stars/900002.jpg", third.wallpapers.single().id)
            assertNull("the band is exhausted", third.nextPage)

            // Page one fetched the root; pages two and three each fetched
            // their card against the warm band cache — no root refetch.
            assertEquals(
                listOf(
                    "https://wallpaperaccess.com/most-popular",
                    "https://wallpaperaccess.com/flowers",
                    "https://wallpaperaccess.com/stars",
                ),
                client.requests,
            )
        }

    @Test
    fun `a fresh instance resuming mid-scroll refetches the root band`() =
        runTest {
            val (fresh, client) =
                freshProvider(
                    linkedMapOf(
                        "https://wallpaperaccess.com/most-popular" to ok(popularGrid + relatedBand("flowers")),
                        "https://wallpaperaccess.com/flowers" to ok(walkedGrid("flowers", "900001")),
                    ),
                )

            // No page one ever ran — the cold cache heals with one extra
            // request, then serves the card.
            val page = fresh.popular(page = 2).getOrThrow()

            assertEquals("flowers/900001.jpg", page.wallpapers.single().id)
            assertNull("one card in the band, nothing after it", page.nextPage)
            assertEquals(
                listOf(
                    "https://wallpaperaccess.com/most-popular",
                    "https://wallpaperaccess.com/flowers",
                ),
                client.requests,
            )
        }

    @Test
    fun `a page past the band answers empty without a request`() =
        runTest {
            val (fresh, client) =
                freshProvider(
                    linkedMapOf(
                        "https://wallpaperaccess.com/most-popular" to ok(popularGrid + relatedBand("flowers")),
                        "https://wallpaperaccess.com/flowers" to ok(walkedGrid("flowers", "900001")),
                    ),
                )
            fresh.popular(page = 1).getOrThrow()
            fresh.popular(page = 2).getOrThrow()

            val past = fresh.popular(page = 3).getOrThrow()

            assertTrue(past.wallpapers.isEmpty())
            assertNull(past.nextPage)
            assertEquals("the band was warm; no request may leave", 2, client.requests.size)
        }

    @Test
    fun `a dead card is skipped with the walk kept alive`() =
        runTest {
            val (fresh, client) =
                freshProvider(
                    linkedMapOf(
                        "https://wallpaperaccess.com/most-popular" to ok(popularGrid + relatedBand("flowers", "stars")),
                        "https://wallpaperaccess.com/flowers" to notFound(),
                        "https://wallpaperaccess.com/stars" to ok(walkedGrid("stars", "900002")),
                    ),
                )

            val skipped = fresh.popular(page = 2).getOrThrow()

            // The site's own card points at a page it no longer serves —
            // an empty page whose nextPage keeps the walk going.
            assertTrue(skipped.wallpapers.isEmpty())
            assertEquals(3, skipped.nextPage)

            val next = fresh.popular(page = 3).getOrThrow()
            assertEquals("stars/900002.jpg", next.wallpapers.single().id)
            assertNull(next.nextPage)
            assertEquals(
                listOf(
                    "https://wallpaperaccess.com/most-popular",
                    "https://wallpaperaccess.com/flowers",
                    "https://wallpaperaccess.com/stars",
                ),
                client.requests,
            )
        }

    @Test
    fun `a listing without a related band ends after its batch`() =
        runTest {
            val (fresh, client) =
                freshProvider(
                    linkedMapOf(
                        "https://wallpaperaccess.com/most-popular" to ok(popularGrid),
                    ),
                )

            val first = fresh.popular(page = 1).getOrThrow()
            assertNull(first.nextPage)

            val second = fresh.popular(page = 2).getOrThrow()

            assertTrue(second.wallpapers.isEmpty())
            assertNull(second.nextPage)
            assertEquals("the empty band was cached; no request may leave", 1, client.requests.size)
        }

    @Test
    fun `a vanished root ends the walk honestly`() =
        runTest {
            val (fresh, _) =
                freshProvider(
                    linkedMapOf(
                        "https://wallpaperaccess.com/fall" to notFound(),
                    ),
                )

            val page = fresh.search(query = "fall", page = 2).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
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
            // A themed root's continuation is its sitemap siblings,
            // resolved lazily on the first deeper page — the offer is
            // optimistic whenever the batch itself is non-empty.
            assertEquals(2, page.nextPage)
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
    fun `a search hit walks its same-theme sitemap siblings`() =
        runTest {
            val (fresh, client) =
                freshProvider(
                    // Longer keys first: /fall is a prefix of /fall-leaves.
                    linkedMapOf(
                        "https://wallpaperaccess.com/sitemap.xml" to
                            ok(sitemapXml("fall-leaves", "waterfall", "space-opera", "fall-landscape")),
                        "https://wallpaperaccess.com/fall-leaves" to ok(walkedGrid("fall-leaves", "900001")),
                        "https://wallpaperaccess.com/fall-landscape" to ok(walkedGrid("fall-landscape", "900003")),
                        "https://wallpaperaccess.com/fall" to ok(fallGrid + relatedBand("galaxy")),
                    ),
                )

            val first = fresh.search(query = "fall", page = 1).getOrThrow()
            assertEquals(2, first.nextPage)

            val second = fresh.search(query = "fall", page = 2).getOrThrow()
            assertEquals("fall-leaves/900001.jpg", second.wallpapers.single().id)
            assertEquals("still one themed sibling left", 3, second.nextPage)

            val third = fresh.search(query = "fall", page = 3).getOrThrow()
            assertEquals("fall-landscape/900003.jpg", third.wallpapers.single().id)
            assertNull("the theme's siblings are exhausted", third.nextPage)

            // The band's off-theme card and the sitemap's off-theme
            // entries (waterfall shares no token, space-opera is another
            // theme) are never fetched.
            assertTrue(
                client.requests.none {
                    it.endsWith("/galaxy") || it.endsWith("/waterfall") || it.endsWith("/space-opera")
                },
            )
        }

    @Test
    fun `a themed tab never serves another theme's wallpapers`() =
        runTest {
            // The 1.1.0 regression, exactly as reported: the Nature tab's
            // scroll drifted into space wallpapers through the site's
            // mixed Related band. The themed walk must stay on theme.
            val (fresh, client) =
                freshProvider(
                    // Longer keys first: /nature is a prefix of /nature-path.
                    linkedMapOf(
                        "https://wallpaperaccess.com/sitemap.xml" to
                            ok(sitemapXml("nature-path", "birds-in-nature", "galaxy", "space-opera", "technology", "car")),
                        "https://wallpaperaccess.com/nature-path" to ok(walkedGrid("nature-path", "900011")),
                        "https://wallpaperaccess.com/birds-in-nature" to ok(walkedGrid("birds-in-nature", "900012")),
                        // The root's own band deals exactly the off-theme
                        // cards the live nature page deals (galaxy,
                        // technology, car, …) — never to be walked now.
                        "https://wallpaperaccess.com/nature" to ok(fallGrid + relatedBand("galaxy", "technology", "car")),
                    ),
                )

            val first = fresh.search(query = "nature", page = 1).getOrThrow()
            assertEquals(2, first.nextPage)

            val second = fresh.search(query = "nature", page = 2).getOrThrow()
            assertEquals("nature-path/900011.jpg", second.wallpapers.single().id)
            assertEquals(3, second.nextPage)

            val third = fresh.search(query = "nature", page = 3).getOrThrow()
            assertEquals("birds-in-nature/900012.jpg", third.wallpapers.single().id)
            assertNull(third.nextPage)

            // No off-theme collection is ever fetched, and the sitemap is
            // read exactly once for the whole walk.
            assertTrue(
                client.requests.none {
                    it.endsWith("/galaxy") ||
                        it.endsWith("/space-opera") ||
                        it.endsWith("/technology") ||
                        it.endsWith("/car")
                },
            )
            assertEquals(1, client.requests.count { it.endsWith("/sitemap.xml") })
        }

    @Test
    fun `a theme the sitemap cannot serve resolves the optimistic offer honestly`() =
        runTest {
            val (fresh, _) =
                freshProvider(
                    linkedMapOf(
                        "https://wallpaperaccess.com/sitemap.xml" to ok(sitemapXml("space-opera", "car")),
                        "https://wallpaperaccess.com/minimal" to ok(fallGrid),
                    ),
                )

            val first = fresh.search(query = "minimal", page = 1).getOrThrow()
            assertEquals("the offer is optimistic", 2, first.nextPage)

            val second = fresh.search(query = "minimal", page = 2).getOrThrow()

            assertTrue("no minimal sibling in the window", second.wallpapers.isEmpty())
            assertNull(second.nextPage)
        }

    @Test
    fun `an unreadable sitemap ends the themed walk without an error`() =
        runTest {
            val (fresh, client) =
                freshProvider(
                    linkedMapOf(
                        "https://wallpaperaccess.com/sitemap.xml" to ProviderHttpResponse(403, emptyMap(), ByteArray(0)),
                        "https://wallpaperaccess.com/nature" to ok(fallGrid),
                    ),
                )

            val first = fresh.search(query = "nature", page = 1).getOrThrow()
            assertEquals(2, first.nextPage)

            val second = fresh.search(query = "nature", page = 2).getOrThrow()

            // A challenge page is zero siblings, never a crash — the
            // feed ends where the site's readable surface ends.
            assertTrue(second.wallpapers.isEmpty())
            assertNull(second.nextPage)
            assertEquals(
                listOf(
                    "https://wallpaperaccess.com/nature",
                    "https://wallpaperaccess.com/sitemap.xml",
                ),
                client.requests,
            )
        }

    @Test
    fun `the sitemap is read once across many tabs`() =
        runTest {
            val (fresh, client) =
                freshProvider(
                    // Longer keys first: /nature and /space are prefixes.
                    linkedMapOf(
                        "https://wallpaperaccess.com/sitemap.xml" to ok(sitemapXml("nature-path", "space-opera")),
                        "https://wallpaperaccess.com/nature-path" to ok(walkedGrid("nature-path", "900011")),
                        "https://wallpaperaccess.com/space-opera" to ok(walkedGrid("space-opera", "900021")),
                        "https://wallpaperaccess.com/nature" to ok(fallGrid),
                        "https://wallpaperaccess.com/space" to ok(fallGrid),
                    ),
                )

            fresh.search(query = "nature", page = 1).getOrThrow()
            fresh.search(query = "nature", page = 2).getOrThrow()
            fresh.search(query = "space", page = 1).getOrThrow()
            fresh.search(query = "space", page = 2).getOrThrow()

            // One sitemap read serves every tab's walk for the session.
            assertEquals(1, client.requests.count { it.endsWith("/sitemap.xml") })
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
    fun `the related band prunes self, non-collections and duplicates`() {
        val band = relatedBand("flowers", "most-popular", "about", "flowers", "stars")

        assertEquals(
            listOf("flowers", "stars"),
            WallpaperAccessParser.parseRelated(band, self = "most-popular"),
        )
    }

    @Test
    fun `a page without a related band answers empty`() {
        assertTrue(WallpaperAccessParser.parseRelated(popularGrid, self = "most-popular").isEmpty())
        assertTrue(WallpaperAccessParser.parseRelated(notFoundPage, self = "most-popular").isEmpty())
    }

    @Test
    fun `a band with no trailing heading still parses inside its window`() {
        val headingless = relatedBand("flowers", "stars").substringBefore("<h2 class=")

        assertEquals(
            listOf("flowers", "stars"),
            WallpaperAccessParser.parseRelated(headingless, self = "most-popular"),
        )
    }

    @Test
    fun `slugify speaks the site's address shape`() {
        assertEquals("fall", WallpaperAccessParser.slugify("  Fall  "))
        assertEquals("4k-gaming", WallpaperAccessParser.slugify("4K GAMING!!"))
        assertEquals("naruto", WallpaperAccessParser.slugify("Naruto"))
        assertEquals("", WallpaperAccessParser.slugify("!!! ..."))
    }

    @Test
    fun `the sitemap keeps only single-segment collection addresses`() {
        val xml =
            sitemapXml(
                "fall",
                "fall", // duplicates keep their first position
                "about", // a page of the site, never a collection
                "nature-path",
                "most-popular/iphone", // multi-segment: not a collection address
                "sitemap.xml", // the sitemap itself
            )

        assertEquals(
            listOf("fall", "nature-path"),
            WallpaperAccessParser.parseSitemap(xml),
        )
    }

    @Test
    fun `a challenge page or empty body parses to zero sitemap slugs`() {
        assertTrue(WallpaperAccessParser.parseSitemap("<!DOCTYPE html><html>Attention Required!</html>").isEmpty())
        assertTrue(WallpaperAccessParser.parseSitemap("").isEmpty())
    }

    @Test
    fun `theme tokens strip the generic vocabulary`() {
        assertEquals(listOf("gaming"), WallpaperAccessParser.themeTokensOf("4k-gaming"))
        assertEquals(listOf("nature"), WallpaperAccessParser.themeTokensOf("nature"))
        assertEquals(listOf("fall", "leaves"), WallpaperAccessParser.themeTokensOf("the-fall-leaves-art"))
        // Words that name nothing a collection is about.
        assertEquals(emptyList<String>(), WallpaperAccessParser.themeTokensOf("4k-wallpapers"))
        assertEquals(emptyList<String>(), WallpaperAccessParser.themeTokensOf("1920x1080"))
        assertEquals(emptyList<String>(), WallpaperAccessParser.themeTokensOf("dual-monitor"))
    }

    @Test
    fun `theme matching rides token boundaries, never substrings`() {
        val fall = listOf("fall")
        assertTrue(WallpaperAccessParser.matchesTheme("fall-leaves", fall))
        assertTrue(WallpaperAccessParser.matchesTheme("autumn-fall", fall))
        assertFalse("waterfall shares no token boundary", WallpaperAccessParser.matchesTheme("waterfall", fall))
        assertFalse(WallpaperAccessParser.matchesTheme("galaxy", fall))

        // Plural flex, both directions.
        val movie = listOf("movie")
        assertTrue(WallpaperAccessParser.matchesTheme("movies", movie))
        assertTrue(WallpaperAccessParser.matchesTheme("the-movie-poster", movie))
        val cars = listOf("cars")
        assertTrue("the singular rides too", WallpaperAccessParser.matchesTheme("teal-car", cars))
        assertFalse("cardiff shares no token boundary", WallpaperAccessParser.matchesTheme("cardiff-city", cars))

        assertFalse(WallpaperAccessParser.matchesTheme("anything", emptyList()))
    }
}

package com.cloudimage.alphacoders

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
 * Alpha Coders provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the live site's markup: the shared
 * schema.org listing grid (ranked feeds and topic pages), the real search
 * endpoint's results grid and its honest empty-grid boundaries, the 404
 * boundaries that end feeds honestly, the big.php detail record with its
 * true dimensions, author, file size and colors, and the host-vocabulary
 * routing all stay covered without a network.
 */
class AlphaCodersWallpaperProviderTest {
    private val provider = AlphaCodersWallpaperProvider()

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

    // Fixtures: shapes captured from alphacoders.com, trimmed to the parts
    // the parsers key on.

    /**
     * The popular feed's first cells — real markup, full schema.org shape:
     * the empty name meta of the ranked feed's cells, the duplicated
     * keywords meta, the boilerplate keyword tail, and a PNG original with
     * its WebP thumbnail.
     */
    private val popularGrid =
        """
        <div id="content_1699286" class="thumb-container-not-computer" itemprop="associatedMedia"
                    itemscope itemtype="http://schema.org/ImageObject">
                <meta itemprop="contentUrl" content="https://images.alphacoders.com/605/605592.png">
            <meta itemprop="url" content="https://wall.alphacoders.com/big.php?i=605592">
            <meta itemprop="datePublished" content="2023-07-17">
            <meta itemprop="uploadDate" content="2023-07-17T10:34:50-07:00">
            <meta itemprop="name" content="">
            <meta itemprop="thumbnailUrl" content="https://images.alphacoders.com/605/thumb-350-605592.webp">
            <meta itemprop="thumbnail" content="https://images.alphacoders.com/605/thumb-350-605592.webp">
            <meta itemprop="keywords" content="Sasuke Uchiha, Naruto Uzumaki, Anime, Naruto, Naruto &amp; Sasuke, desktop wallpaper, background, hd wallpaper, 8k ultra hd, 8k ultra hd wallpaper">

                    <meta itemprop="caption description" content="HD wallpaper featuring Sasuke Uchiha and Naruto Uzumaki from the anime Naruto, depicted in a dynamic and colorful battle scene.">

            <meta itemprop="author" content="DeviousSketcher">

            <meta itemprop="keywords" content="Sasuke Uchiha, Naruto Uzumaki, Anime, Naruto, Naruto &amp; Sasuke, desktop wallpaper, background, hd wallpaper, 8k ultra hd, 8k ultra hd wallpaper">

            <div class="center">
                            <a href="https://wall.alphacoders.com/big.php?i=605592" title="HD wallpaper featuring Sasuke Uchiha and Naruto Uzumaki from the anime Naruto.">
                        <img
                            width="350"
                            height="219"
                            alt="HD wallpaper featuring Sasuke Uchiha and Naruto Uzumaki from the anime Naruto."
                            src="https://images.alphacoders.com/605/thumb-350-605592.webp">
                    </a>
            </div>
        </div>
        <div id="content_3667501" class="thumb-container-not-computer" itemprop="associatedMedia"
                    itemscope itemtype="http://schema.org/ImageObject">
                <meta itemprop="contentUrl" content="https://images2.alphacoders.com/141/1413717.jpg">
            <meta itemprop="url" content="https://wall.alphacoders.com/big.php?i=1413717">
            <meta itemprop="name" content="Tanjiro Kamado">
            <meta itemprop="thumbnailUrl" content="https://images2.alphacoders.com/141/thumb-350-1413717.webp">
            <meta itemprop="keywords" content="Tanjiro Kamado, Anime, desktop wallpaper, background, hd wallpaper">
            <div class="center">
                            <a href="https://wall.alphacoders.com/big.php?i=1413717" title="Tanjiro Kamado from the anime.">
                        <img
                            width="350"
                            height="219"
                            alt="Tanjiro Kamado from the anime."
                            src="https://images2.alphacoders.com/141/thumb-350-1413717.webp">
                    </a>
            </div>
        </div>
        """.trimIndent()

    /**
     * A malformed cell wedged between two good ones: no contentUrl, no url
     * meta — the site's own debris shape after an ad slot or a lazy-load
     * placeholder. Its neighbors must survive it.
     */
    private val gridWithDebris =
        popularGrid.substringBefore("<div id=\"content_3667501\"") +
            """
            <div class="thumb-container-not-computer" itemprop="associatedMedia"
                        itemscope itemtype="http://schema.org/ImageObject">
                    <meta itemprop="name" content="Broken Cell">
            </div>
            """.trimIndent() +
            popularGrid.substringAfter("<div id=\"content_3667501\"")

    /**
     * The same cell with flipped meta attribute order and cramped
     * whitespace — the parser must be attribute-order agnostic.
     */
    private val flippedAttrsGrid =
        """
        <div class="thumb-container-not-computer" itemprop="associatedMedia" itemscope itemtype="http://schema.org/ImageObject">
            <meta content="https://images4.alphacoders.com/132/1328396.png" itemprop="contentUrl">
            <meta content="https://wall.alphacoders.com/big.php?i=1328396" itemprop="url">
            <meta content="https://images4.alphacoders.com/132/thumb-350-1328396.webp" itemprop="thumbnailUrl">
            <meta content="Itachi Uchiha, manga, Anime, Naruto, desktop wallpaper, background, hd wallpaper" itemprop="keywords">
        </div>
        """.trimIndent()

    /**
     * The real search results grid for "indian actress" — captured from
     * the site's own search endpoint (`/search/view?q=indian+actress`),
     * the exact shape 1.1.0's search rides. Same schema.org cell as the
     * listings; the search grid's container class differs
     * (`thumb-container-wallpaper-desktop` vs the feeds'
     * `-not-computer`), which the parser is agnostic to — it splits on
     * the itemprop, never the class.
     */
    private val searchGrid =
        """
        <div id="content_1453228" class="thumb-container-wallpaper-desktop" itemprop="associatedMedia"
                    itemscope itemtype="http://schema.org/ImageObject">
                <meta itemprop="contentUrl" content="https://images3.alphacoders.com/269/269807.jpg">
            <meta itemprop="url" content="https://wall.alphacoders.com/big.php?i=269807">
            <meta itemprop="name" content="Umesh Yadav">
            <meta itemprop="thumbnailUrl" content="https://images3.alphacoders.com/269/thumb-350-269807.webp">
            <meta itemprop="keywords" content="India, Indian, yadav, cricket, Sports, desktop wallpaper, background, hd wallpaper">
        </div>
        <div id="content_3222107" class="thumb-container-wallpaper-desktop" itemprop="associatedMedia"
                    itemscope itemtype="http://schema.org/ImageObject">
                <meta itemprop="contentUrl" content="https://images2.alphacoders.com/686/686465.jpg">
            <meta itemprop="url" content="https://wall.alphacoders.com/big.php?i=686465">
            <meta itemprop="name" content="Stunning Actress Jewelry HD Wallpaper">
            <meta itemprop="thumbnailUrl" content="https://images2.alphacoders.com/686/thumb-350-686465.webp">
            <meta itemprop="keywords" content="jewelry, brown eyes, brunette, Indian, actress, Celebrity, Sonam Kapoor, desktop wallpaper, background, hd wallpaper">
        </div>
        """.trimIndent()

    /**
     * A wallpaper's big.php page — the page-level name meta, the
     * mainEntity block with its href-shaped contentUrl and the
     * main-content img carrying TRUE dimensions, the author meta, the File
     * Info box with an MB size, the color row, and one related cell after
     * it (whose own metas must never win).
     */
    private val detailPage =
        """
        <head>
            <meta itemprop="name" content="Download Tanjiro Kamado Anime 4k Ultra HD Wallpaper | 3840x2400 | Wallpaper Abyss">
            <meta itemprop="image" content="https://images2.alphacoders.com/141/thumb-1920-1413717.jpg">
        </head>
        <div class="center" itemprop="mainEntity" itemscope itemtype="http://schema.org/ImageObject">
                <meta itemprop="contentUrl" href="https://images2.alphacoders.com/141/1413717.jpg">
            <meta itemprop="representativeOfPage" content="true">
            <meta itemprop="datePublished" content="2026-09-02">
            <meta itemprop="name" content="">
            <meta itemprop="thumbnailUrl" content="https://images2.alphacoders.com/141/thumb-350-1413717.webp">
            <meta itemprop="keywords" content="Tanjiro Kamado, Anime, desktop wallpaper, background, hd wallpaper, 8k ultra hd, 8k ultra hd wallpaper">
            <meta itemprop="caption description" content="Tanjiro Kamado from the anime stands on a wooden balcony at sunset.">
            <meta itemprop="author" content="Reku">
            <picture>
                <img id="main-content" class="main-content"
                            width="3840"
                            height="2400"
                            src="https://images2.alphacoders.com/141/thumb-1920-1413717.jpg"
                            title="Download Tanjiro Kamado Anime 4k Ultra HD Wallpaper">
            </picture>
        </div>
        <div class="box-dark-no-margin">
            <h5 class="no-margin" title="File Info">
                3840x2400
                1.38 MB
                JPG
            </h5>
        </div>
        <a class='color-info color-infos1' onclick="contentByColor('010101')" style='background-color:#010101; color: #010101;' > #010101 </a>
        <a class='color-info color-infos2' onclick="contentByColor('4f4f4e')" style='background-color:#4f4f4e; color: #4f4f4e;' > #4f4f4e </a>
        <a class='color-info color-infos3' onclick="contentByColor('fdfdfd')" style='background-color:#fdfdfd; color: #fdfdfd;' > #fdfdfd </a>
        <div id="content_999" class="thumb-container-not-computer" itemprop="associatedMedia" itemscope itemtype="http://schema.org/ImageObject">
                <meta itemprop="contentUrl" content="https://images2.alphacoders.com/139/1399041.jpg">
            <meta itemprop="url" content="https://wall.alphacoders.com/big.php?i=1399041">
            <meta itemprop="name" content="Related Cell">
            <meta itemprop="thumbnailUrl" content="https://images2.alphacoders.com/139/thumb-350-1399041.webp">
            <meta itemprop="keywords" content="Demon Slayer, desktop wallpaper">
        </div>
        """.trimIndent()

    /**
     * A PNG wallpaper's page with the by-author title form and a KB size —
     * the second real shape.
     */
    private val pngDetailPage =
        """
        <meta itemprop="name" content="Itachi Uchiha Manga Style Wallpaper by patrika | 2205x1080 | Wallpaper Abyss">
        <meta itemprop="contentUrl" href="https://images3.alphacoders.com/132/1328396.png">
        <meta itemprop="author" content="patrika">
        <img id="main-content" class="main-content"
                            width="2205"
                            height="1080"
                            src="https://images3.alphacoders.com/132/thumb-1920-1328396.png"
                            title="Itachi Uchiha Manga Style Wallpaper by patrika">
        <h5 class="no-margin" title="File Info"> 2205x1080 1.80 MB PNG </h5>
        """.trimIndent()

    // ------------------------------------------------------------ the feeds

    @Test
    fun `popular serves the ranking's mapped grid items`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/popular-wallpapers" to ok(popularGrid),
                    ),
                )

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals("https://alphacoders.com/popular-wallpapers", client.requests.single())
            assertEquals(2, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertEquals("605592", first.id)
            assertEquals("cloudimage.alphacoders", first.providerId)
            assertEquals("https://images.alphacoders.com/605/thumb-350-605592.webp", first.thumbUrl)
            assertEquals("https://images.alphacoders.com/605/605592.png", first.fullUrl)
            // The cell's name meta is empty on the ranked feed, so the
            // first real keyword is the title.
            assertEquals("Sasuke Uchiha", first.title)
            assertTrue(first.tags.contains("Anime"))
            assertTrue(first.tags.contains("Naruto & Sasuke"))
            // Regression guard for the uniform card-crop trap: the listing
            // publishes 350x219 on EVERY cell, true of no wallpaper, so the
            // grid must carry no dimensions — details()'s alone are true.
            assertNull(first.width)
            assertNull(first.height)
            assertEquals(2, page.nextPage)
        }

    @Test
    fun `a filled name meta wins as the title`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/popular-wallpapers" to ok(popularGrid),
                ),
            )

            val second = provider.popular(page = 1).getOrThrow().wallpapers[1]

            assertEquals("1413717", second.id)
            assertEquals("Tanjiro Kamado", second.title)
            assertEquals("https://images2.alphacoders.com/141/1413717.jpg", second.fullUrl)
        }

    @Test
    fun `boilerplate keywords never ride as tags`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/popular-wallpapers" to ok(popularGrid),
                ),
            )

            val first =
                provider
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()
            val boilerplate =
                setOf(
                    "desktop wallpaper",
                    "background",
                    "hd wallpaper",
                    "8k ultra hd",
                    "8k ultra hd wallpaper",
                )

            assertTrue(
                "the boilerplate tail must be filtered",
                first.tags.none { it.lowercase() in boilerplate },
            )
        }

    @Test
    fun `a malformed cell degrades to itself, never its neighbors`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/popular-wallpapers" to ok(gridWithDebris),
                ),
            )

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals(listOf("605592", "1413717"), page.wallpapers.map { it.id })
        }

    @Test
    fun `flipped meta attribute order parses identically`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/popular-wallpapers" to ok(flippedAttrsGrid),
                ),
            )

            val page = provider.popular(page = 1).getOrThrow()

            val only = page.wallpapers.single()
            assertEquals("1328396", only.id)
            assertEquals("https://images4.alphacoders.com/132/1328396.png", only.fullUrl)
            assertEquals("https://images4.alphacoders.com/132/thumb-350-1328396.webp", only.thumbUrl)
            assertEquals(listOf("Itachi Uchiha", "manga", "Anime", "Naruto"), only.tags)
        }

    @Test
    fun `pagination rides the page parameter`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/popular-wallpapers?page=3" to ok(popularGrid),
                        "https://alphacoders.com/popular-wallpapers" to ok(popularGrid),
                    ),
                )

            val page = provider.popular(page = 3).getOrThrow()

            assertEquals("https://alphacoders.com/popular-wallpapers?page=3", client.requests.single())
            assertEquals(4, page.nextPage)
        }

    @Test
    fun `a 404 past the end ends the feed honestly`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/popular-wallpapers?page=2" to notFound(),
                    ),
                )

            val page = provider.popular(page = 2).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertEquals(1, client.requests.size)
        }

    @Test
    fun `an empty 200 never advertises more`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/popular-wallpapers" to ok("<html><body>no cells</body></html>"),
                ),
            )

            val page = provider.popular(page = 1).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun `a 404 on a known feed's first page is a source failure`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/popular-wallpapers" to notFound(),
                ),
            )

            val result = provider.popular(page = 1)

            assertTrue(result.isFailure)
        }

    @Test
    fun `the deep cap answers empty without a request`() =
        runTest {
            val client = configureWith(emptyMap())

            val page = provider.popular(page = 101).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue(client.requests.isEmpty())
        }

    // ------------------------------------------------------------- routing

    @Test
    fun `host categories walk their own topic pages`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/anime-wallpapers" to ok(popularGrid),
                        "https://alphacoders.com/people-wallpapers" to ok(popularGrid),
                    ),
                )

            provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            provider.popular(page = 1, filters = Filters.of("category" to "people")).getOrThrow()

            assertEquals(
                listOf(
                    "https://alphacoders.com/anime-wallpapers",
                    "https://alphacoders.com/people-wallpapers",
                ),
                client.requests,
            )
        }

    @Test
    fun `sorting date walks the newest feed`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/newest-wallpapers" to ok(popularGrid),
                    ),
                )

            provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()

            assertEquals("https://alphacoders.com/newest-wallpapers", client.requests.single())
        }

    @Test
    fun `search rides the site's real search endpoint`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/search/view?q=Naruto&type=wallpaper" to ok(searchGrid),
                        "https://alphacoders.com/search/view?q=4K+Gaming%21&type=wallpaper" to ok(searchGrid),
                        "https://alphacoders.com/popular-wallpapers" to ok(popularGrid),
                    ),
                )

            provider.search(query = "Naruto").getOrThrow()
            provider.search(query = "4K Gaming!").getOrThrow()
            provider.search(query = "   ").getOrThrow()

            assertEquals(
                listOf(
                    "https://alphacoders.com/search/view?q=Naruto&type=wallpaper",
                    "https://alphacoders.com/search/view?q=4K+Gaming%21&type=wallpaper",
                    "https://alphacoders.com/popular-wallpapers",
                ),
                client.requests,
            )
        }

    @Test
    fun `a search hit serves the site's own ranked matches`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/search/view?q=indian+actress&type=wallpaper" to ok(searchGrid),
                ),
            )

            val page = provider.search(query = "indian actress").getOrThrow()

            assertEquals(2, page.wallpapers.size)
            val actress = page.wallpapers[1]
            assertEquals("686465", actress.id)
            assertEquals("Stunning Actress Jewelry HD Wallpaper", actress.title)
            assertEquals("https://images2.alphacoders.com/686/686465.jpg", actress.fullUrl)
            assertEquals("https://images2.alphacoders.com/686/thumb-350-686465.webp", actress.thumbUrl)
            // The keyword row minus its boilerplate tail rides as tags
            // (capped at six per cell — "Sonam Kapoor" is the row's
            // seventh subject and lives in details' fuller record).
            assertTrue(actress.tags.contains("actress"))
            assertTrue(actress.tags.contains("Indian"))
            assertEquals(2, page.nextPage)
        }

    @Test
    fun `a no-match query answers the site's honest empty grid`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/search/view?q=zzqwxywhatever&type=wallpaper" to
                            ok("""<html><body><h1>No Results</h1></body></html>"""),
                    ),
                )

            val page = provider.search(query = "zzqwxywhatever").getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertEquals(
                "https://alphacoders.com/search/view?q=zzqwxywhatever&type=wallpaper",
                client.requests.single(),
            )
        }

    @Test
    fun `search pages ride the page parameter`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/search/view?q=indian+actress&type=wallpaper&page=2" to ok(searchGrid),
                    ),
                )

            val page = provider.search(query = "indian actress", page = 2).getOrThrow()

            assertEquals(
                "https://alphacoders.com/search/view?q=indian+actress&type=wallpaper&page=2",
                client.requests.single(),
            )
            assertEquals(3, page.nextPage)
        }

    @Test
    fun `an empty grid past the search's end never advertises more`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/search/view?q=indian+actress&type=wallpaper&page=50" to
                        ok("<html><body></body></html>"),
                ),
            )

            val page = provider.search(query = "indian actress", page = 50).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun `a 404 from the search endpoint is an honest miss, never a crash`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/search/view?q=anything&type=wallpaper" to notFound(),
                ),
            )

            val page = provider.search(query = "anything").getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun `the search deep cap answers empty without a request`() =
        runTest {
            val client = configureWith(emptyMap())

            val page = provider.search(query = "anything", page = 101).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue(client.requests.isEmpty())
        }

    // ------------------------------------------------------------ sections

    @Test
    fun `sections offer sixteen shelves with search-ready presets`() =
        runTest {
            val sections = provider.sections()

            fun presetQuery(id: String): String =
                sections
                    .first { it.id == id }
                    .filters
                    .valuesFor("query")
                    .single()

            assertEquals(16, sections.size)
            assertEquals("popular", sections.first().id)
            assertEquals("Popular", sections.first().title)
            // The presets that must dodge redirecting plurals.
            assertEquals("car", presetQuery("cars"))
            assertEquals("video game", presetQuery("games"))
            assertEquals("movie", presetQuery("movies"))
            assertEquals("animal", presetQuery("animals"))
            assertEquals("minimalist", presetQuery("minimal"))
        }

    @Test
    fun `every section preset rides the site's real search`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/search/view?q=" to ok(searchGrid),
                    ),
                )

            provider
                .sections()
                .filter { it.filters.valuesFor("query").isNotEmpty() }
                .forEach { section ->
                    provider.search(query = section.filters.valuesFor("query").single()).getOrThrow()
                }
            assertEquals(12, client.requests.size)
            assertTrue(client.requests.all { it.startsWith("https://alphacoders.com/search/view?q=") })
        }

    // ------------------------------------------------------------- details

    @Test
    fun `details reads the definitive record`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://wall.alphacoders.com/big.php?i=1413717" to ok(detailPage),
                    ),
                )

            val details = provider.details("1413717").getOrThrow()

            assertEquals("https://wall.alphacoders.com/big.php?i=1413717", client.requests.single())
            assertEquals("1413717", details.wallpaper.id)
            // The mainEntity's original, never the related cell's.
            assertEquals("https://images2.alphacoders.com/141/1413717.jpg", details.wallpaper.fullUrl)
            assertEquals("https://images2.alphacoders.com/141/thumb-1920-1413717.jpg", details.wallpaper.thumbUrl)
            assertEquals(3840, details.wallpaper.width)
            assertEquals(2400, details.wallpaper.height)
            assertEquals("3840x2400", details.resolution)
            assertEquals("Tanjiro Kamado Anime 4k Ultra HD Wallpaper", details.wallpaper.title)
            assertEquals("Reku", details.author)
            assertEquals(1.38 * 1024 * 1024, details.fileSizeBytes!!.toDouble(), 1.0)
            assertTrue(details.wallpaper.tags.contains("Anime"))
            // The contract's detail payload carries no description field;
            // the parser's record is where the site's caption lands.
            assertEquals(
                "Tanjiro Kamado from the anime stands on a wooden balcony at sunset.",
                AlphaCodersParser.parseDetail(detailPage)!!.description,
            )
            assertEquals(listOf("#010101", "#4f4f4e", "#fdfdfd"), details.wallpaper.colors)
            assertEquals("https://wall.alphacoders.com/big.php?i=1413717", details.sourceUrl)
        }

    @Test
    fun `a PNG page keeps its extension and strips the by-author title`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://wall.alphacoders.com/big.php?i=1328396" to ok(pngDetailPage),
                ),
            )

            val details = provider.details("1328396").getOrThrow()

            assertEquals("https://images3.alphacoders.com/132/1328396.png", details.wallpaper.fullUrl)
            assertEquals("https://images3.alphacoders.com/132/thumb-1920-1328396.png", details.wallpaper.thumbUrl)
            assertEquals(2205, details.wallpaper.width)
            assertEquals(1080, details.wallpaper.height)
            assertEquals("Itachi Uchiha Manga Style Wallpaper", details.wallpaper.title)
            assertEquals("patrika", details.author)
            assertEquals(1.80 * 1024 * 1024, details.fileSizeBytes!!.toDouble(), 1.0)
        }

    @Test
    fun `a KB file size parses to bytes`() =
        runTest {
            val kbPage =
                pngDetailPage.replace(
                    "2205x1080 1.80 MB PNG",
                    "1920x1080 845 KB JPG",
                )
            configureWith(
                linkedMapOf(
                    "https://wall.alphacoders.com/big.php?i=1328396" to ok(kbPage),
                ),
            )

            val details = provider.details("1328396").getOrThrow()

            assertEquals(845L * 1024, details.fileSizeBytes)
        }

    @Test
    fun `a page without any identifying shape is unrecognized`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://wall.alphacoders.com/big.php?i=1" to ok("<html><body>redesigned</body></html>"),
                ),
            )

            assertTrue(provider.details("1").isFailure)
        }

    @Test
    fun `a missing author degrades honestly`() =
        runTest {
            val noAuthor = detailPage.replace("""<meta itemprop="author" content="Reku">""", "")
            configureWith(
                linkedMapOf(
                    "https://wall.alphacoders.com/big.php?i=1413717" to ok(noAuthor),
                ),
            )

            val details = provider.details("1413717").getOrThrow()

            assertNull(details.author)
            // No author to strip from the img title.
            assertEquals("Tanjiro Kamado Anime 4k Ultra HD Wallpaper", details.wallpaper.title)
        }

    // ------------------------------------------------------ random + tags

    @Test
    fun `random serves the newest feed's first batch`() =
        runTest {
            val client =
                configureWith(
                    linkedMapOf(
                        "https://alphacoders.com/newest-wallpapers" to ok(popularGrid),
                    ),
                )

            val batch = provider.random().getOrThrow()

            assertEquals("https://alphacoders.com/newest-wallpapers", client.requests.single())
            assertEquals(2, batch.size)
        }

    @Test
    fun `suggestTags answers only from tags this instance has seen`() =
        runTest {
            configureWith(
                linkedMapOf(
                    "https://alphacoders.com/popular-wallpapers" to ok(popularGrid),
                ),
            )
            provider.popular(page = 1).getOrThrow()

            val suggestions = provider.suggestTags("nar").getOrThrow()

            assertTrue(suggestions.contains("naruto"))
            assertTrue(provider.suggestTags("zzz").getOrThrow().isEmpty())
        }

    @Test
    fun `a fresh instance suggests nothing`() =
        runTest {
            assertTrue(provider.suggestTags("nar").getOrThrow().isEmpty())
        }
}

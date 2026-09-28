package com.cloudimage.wallpaperaccess

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live check against wallpaperaccess.com, for maintenance: the fixture
 * tests pin the markup shapes, this answers whether the SITE still speaks
 * them.
 *
 * Run on demand with:
 *
 * `WALLPAPERACCESS_LIVE=1 ./gradlew :providers:wallpaperaccess:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 */
class WallpaperAccessLiveCheckTest {
    private val provider = WallpaperAccessWallpaperProvider()

    /**
     * The plugin facade over HttpURLConnection, sending the app's own
     * User-Agent — the same identity [com.cloudimage.core.network] sends,
     * and one the site verifiably serves without challenge.
     */
    private class LiveClient : ProviderHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)",
            )
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val body =
                (if (status in 200..399) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() }
                    ?: ByteArray(0)
            // The null key carries the status line; the response type has no
            // room for it and nothing reads it.
            val headers = connection.headerFields.filterKeys { it != null }
            connection.disconnect()
            return ProviderHttpResponse(status, headers, body)
        }
    }

    private fun live(): Boolean = System.getenv("WALLPAPERACCESS_LIVE") == "1"

    private fun configured(): WallpaperAccessWallpaperProvider = provider.apply { configure(LiveClient(), ProviderSettings { null }) }

    @Test
    fun `popular feed answers with a real single-page batch`() =
        runTest {
            assumeTrue(live())
            val page = configured().popular(page = 1).getOrThrow()

            assertTrue("expected the ranking's batch, got ${page.wallpapers.size}", page.wallpapers.size >= 40)
            val first = page.wallpapers.first()
            assertTrue("unexpected id shape: ${first.id}", Regex("""^[a-z0-9-]+/\d+\.[a-z0-9]+$""").matches(first.id))
            assertTrue(first.thumbUrl.startsWith("https://wallpaperaccess.com/thumb/"))
            assertTrue(first.fullUrl.startsWith("https://wallpaperaccess.com/full/"))
            // The site's listings publish the TRUE dimensions themselves —
            // the whole reason this provider can fill the info sheet for
            // free. Every item must carry them.
            assertTrue(
                "listing items must publish true dimensions (${first.width}x${first.height})",
                page.wallpapers.all { it.width != null && it.height != null },
            )
            // The listing's own batch is the whole inventory, but its
            // Related Wallpapers band seeds the endless walk.
            assertEquals("the related band seeds the walk", 2, page.nextPage)
        }

    @Test
    fun `data-or dimensions equal the served file's own pixels`() =
        runTest {
            assumeTrue(live())
            val first =
                configured()
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()

            // One image fetch, exactly what rendering the item costs.
            val response = LiveClient().get(first.fullUrl)
            assertTrue("original answered HTTP ${response.statusCode}", response.isSuccessful)
            val sniffed = sniffDimensions(response.body)
            assertNotNull("original should be a JPEG or PNG", sniffed)
            if (sniffed == null) return@runTest
            val (width, height) = sniffed
            assertEquals(
                "data-or must equal the file's true width (the card-crop regression, inverted)",
                first.width,
                width,
            )
            assertEquals("data-or must equal the file's true height", first.height, height)
            assertTrue(
                "original should be a real file, got ${response.body.size} bytes",
                response.body.size > 100_000,
            )
        }

    @Test
    fun `the thumb really is the lighter preview of the same file`() =
        runTest {
            assumeTrue(live())
            val first =
                configured()
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()

            val thumb = LiveClient().get(first.thumbUrl)
            assertTrue("thumb answered HTTP ${thumb.statusCode}", thumb.isSuccessful)
            assertTrue(
                "thumb is an image, got ${thumb.header("Content-Type").orEmpty()}",
                thumb.header("Content-Type").orEmpty().startsWith("image/"),
            )
            val full = LiveClient().get(first.fullUrl)
            assertTrue(
                "the preview should be lighter than the original (${thumb.body.size} vs ${full.body.size})",
                thumb.body.size < full.body.size,
            )
        }

    @Test
    fun `the related band walk serves a real second batch`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val first = configured.popular(page = 1).getOrThrow()
            assumeTrue("the ranking carries no related band right now", first.nextPage != null)

            val second = configured.popular(page = 2).getOrThrow()

            // Page two is the band's first card — a sibling collection's
            // whole batch, not the root's again.
            assertTrue(
                "expected a walked batch, got ${second.wallpapers.size}",
                second.wallpapers.size >= 10,
            )
            val rootId = first.wallpapers.first().id
            val rootPrefix = rootId.substringBefore('/')
            assertTrue(
                "the walked batch should come from a sibling collection, not '$rootPrefix'",
                second.wallpapers.none { it.id.startsWith("$rootPrefix/") },
            )
            assertTrue(
                "walked items must publish true dimensions too",
                second.wallpapers.all { it.width != null && it.height != null },
            )
        }

    @Test
    fun `the fresh feed and the anime category walk their own listings`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val fresh =
                configured
                    .popular(page = 1, filters = Filters.of("sorting" to "date"))
                    .getOrThrow()
            val anime =
                configured
                    .popular(page = 1, filters = Filters.of("category" to "anime"))
                    .getOrThrow()

            assertTrue("expected the fresh batch, got ${fresh.wallpapers.size}", fresh.wallpapers.size >= 20)
            assertTrue(fresh.wallpapers.all { it.id.startsWith("new/") })
            assertTrue("expected the anime collection, got ${anime.wallpapers.size}", anime.wallpapers.size >= 20)
            assertTrue(anime.wallpapers.all { it.id.startsWith("anime/") })
        }

    @Test
    fun `search slug-guesses a real collection and misses honestly`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val hit = configured.search(query = "naruto", page = 1).getOrThrow()
            val miss = configured.search(query = "definitely-not-a-collection-xyzzy", page = 1).getOrThrow()

            assertTrue("expected a real batch, got ${hit.wallpapers.size}", hit.wallpapers.size >= 20)
            assertTrue(hit.wallpapers.all { it.id.startsWith("naruto/") })
            // A hit optimistically offers its same-theme walk.
            assertEquals("the hit's theme walk is offered", 2, hit.nextPage)
            // An unknown address is a miss, not a failure.
            assertTrue("expected an honest empty page", miss.wallpapers.isEmpty())
            assertEquals(null, miss.nextPage)
        }

    @Test
    fun `details re-walks the listing and answers the true record`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val first =
                configured
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()

            val details = configured.details(first.id).getOrThrow()

            assertEquals(first.id, details.wallpaper.id)
            assertEquals(first.fullUrl, details.wallpaper.fullUrl)
            assertEquals(first.width, details.wallpaper.width)
            assertEquals(first.height, details.wallpaper.height)
            assertTrue(
                "expected the true resolution, got ${details.resolution}",
                Regex("""\d{3,5}x\d{3,5}""").matches(details.resolution.orEmpty()),
            )
            // The site's own share address: the collection, scrolled to the item.
            assertTrue(details.sourceUrl!!.endsWith("#${first.id.substringAfter('/').substringBefore('.')}"))
        }

    @Test
    fun `a portrait item keeps its portrait shape from listing to details`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            // Phone-first crops live in the phone-wallpaper collections;
            // anime reliably carries a portrait item or two.
            val portrait =
                configured
                    .popular(page = 1, filters = Filters.of("category" to "anime"))
                    .getOrThrow()
                    .wallpapers
                    .firstOrNull { (it.height ?: 0) > (it.width ?: 0) }

            // Not an assertion about the site — just skip when the first
            // batch happens to be all-landscape.
            assumeTrue("no portrait item in this batch", portrait != null)
            if (portrait == null) return@runTest
            val details = configured.details(portrait.id).getOrThrow()
            assertEquals(portrait.height, details.wallpaper.height)
            assertTrue(
                "portrait original mislabeled: ${details.wallpaper.width}x${details.wallpaper.height}",
                (details.wallpaper.height ?: 0) > (details.wallpaper.width ?: 0),
            )
        }

    @Test
    fun `a themed tab stays on theme through deep scroll pages`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val first = configured.search(query = "nature", page = 1).getOrThrow()
            assumeTrue("no continuation offered right now", first.nextPage != null)

            // The 1.1.0 regression, inverted: every deep page of the
            // Nature tab must come from a collection the theme names —
            // the mixed Related band (galaxy, technology, …) is never
            // walked for a themed root.
            val theme = listOf("nature")
            var deepBatches = 0
            var nextPage = first.nextPage
            while (nextPage != null && deepBatches < 3) {
                val page = configured.search(query = "nature", page = nextPage).getOrThrow()
                val collection =
                    page.wallpapers
                        .firstOrNull()
                        ?.id
                        ?.substringBefore('/')
                if (collection != null) {
                    deepBatches++
                    assertTrue(
                        "'$collection' is off the nature theme — the 1.1.0 regression",
                        WallpaperAccessParser.matchesTheme(collection, theme),
                    )
                    assertTrue(
                        "every item of the batch comes from '$collection'",
                        page.wallpapers.all { it.id.startsWith("$collection/") },
                    )
                }
                assertTrue(
                    "a deep page brought nothing yet still offered more — the 1.2.0 stall",
                    page.wallpapers.isNotEmpty() || page.nextPage == null,
                )
                nextPage = page.nextPage
            }
            assertTrue(
                "expected at least two deep nature batches, got $deepBatches",
                deepBatches >= 2,
            )
        }

    @Test
    fun `the anime tab's deep pages stay on theme without stalling`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val first = configured.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            assumeTrue("no continuation offered right now", first.nextPage != null)

            // The tab the 1.2.0 stall was reported on: its deep pages
            // must serve on-theme batches from the sitemap walk, and a
            // page that brings nothing must end the walk — never an
            // empty page that still claims more.
            val theme = listOf("anime")
            var deepBatches = 0
            var nextPage = first.nextPage
            while (nextPage != null && deepBatches < 2) {
                val page =
                    configured
                        .popular(page = nextPage, filters = Filters.of("category" to "anime"))
                        .getOrThrow()
                assertTrue(
                    "a deep page brought nothing yet still offered more — the 1.2.0 stall",
                    page.wallpapers.isNotEmpty() || page.nextPage == null,
                )
                page.wallpapers.firstOrNull()?.id?.substringBefore('/')?.let { collection ->
                    deepBatches++
                    assertTrue(
                        "'$collection' is off the anime theme",
                        WallpaperAccessParser.matchesTheme(collection, theme),
                    )
                }
                nextPage = page.nextPage
            }
            assertTrue("expected at least one deep anime batch, got $deepBatches", deepBatches >= 1)
        }

    // --------------------------------------------------- image dimension sniff

    /**
     * The true dimensions of a served image, straight from its header
     * bytes — JPEG SOF markers or PNG IHDR, no imaging library. Test-only:
     * it verifies the site's `data-or` claim against the file itself, the
     * manual check this provider's whole design bet was validated with.
     */
    private fun sniffDimensions(bytes: ByteArray): Pair<Int, Int>? =
        when {
            bytes.size > 24 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> jpegDimensions(bytes)
            bytes.size > 24 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> pngDimensions(bytes)
            else -> null
        }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            // SOF0..SOF3 carry the frame header; anything else is skipped
            // by its own length.
            if (marker in 0xC0..0xC3) {
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return width to height
            }
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (length < 2) return null
            i += 2 + length
        }
        return null
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
        val width =
            ((bytes[16].toInt() and 0xFF) shl 24) or ((bytes[17].toInt() and 0xFF) shl 16) or
                ((bytes[18].toInt() and 0xFF) shl 8) or (bytes[19].toInt() and 0xFF)
        val height =
            ((bytes[20].toInt() and 0xFF) shl 24) or ((bytes[21].toInt() and 0xFF) shl 16) or
                ((bytes[22].toInt() and 0xFF) shl 8) or (bytes[23].toInt() and 0xFF)
        return width to height
    }
}

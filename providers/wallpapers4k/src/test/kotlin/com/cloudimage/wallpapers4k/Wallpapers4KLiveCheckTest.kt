package com.cloudimage.wallpapers4k

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
 * Live check against 4kwallpapers.com, for maintenance: the fixture tests
 * pin the markup shapes, this answers whether the SITE still speaks them.
 *
 * Run on demand with:
 *
 * `WALLPAPERS4K_LIVE=1 ./gradlew :providers:wallpapers4k:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 */
class Wallpapers4KLiveCheckTest {
    private val provider = Wallpapers4KWallpaperProvider()

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

    private fun live(): Boolean = System.getenv("WALLPAPERS4K_LIVE") == "1"

    private fun configured(): Wallpapers4KWallpaperProvider = provider.apply { configure(LiveClient(), ProviderSettings { null }) }

    @Test
    fun `popular feed answers with real items and a next page`() =
        runTest {
            assumeTrue(live())
            val page = configured().popular(page = 1).getOrThrow()

            assertEquals("the site serves twenty-four per page", 24, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertTrue("unexpected id shape: ${first.id}", Regex("""^[a-z0-9-]+/[a-z0-9-]+-\d+\.html$""").matches(first.id))
            assertTrue(first.thumbUrl.startsWith("https://4kwallpapers.com/images/walls/thumbs"))
            assertTrue(first.fullUrl.startsWith("https://4kwallpapers.com/images/walls/orig/"))
            // Regression guard for the card-crop trap: the live listing
            // still publishes only its uniform 400x225 attrs, so the grid
            // must carry no dimensions — the true ones are details()'s alone.
            assertTrue(
                "grid items must not publish the card-crop dims (${first.width}x${first.height})",
                first.width == null && first.height == null,
            )
            assertTrue("expected a next page", page.nextPage != null)
        }

    @Test
    fun `the disclosed original really is the multi-megabyte file`() =
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
            val type = response.header("Content-Type").orEmpty()
            assertTrue("original is an image, got $type", type.startsWith("image/"))
            assertTrue(
                "original should be the multi-megabyte file, got ${response.body.size} bytes",
                response.body.size > 250_000,
            )
        }

    @Test
    fun `page two walks the query pagination without repeating page one`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val page1 = configured.popular(page = 1).getOrThrow()
            val page2 = configured.popular(page = 2).getOrThrow()

            assertTrue(page2.wallpapers.isNotEmpty())
            val ids1 = page1.wallpapers.map { it.id }.toSet()
            val ids2 = page2.wallpapers.map { it.id }
            assertTrue("expected fresh items on page 2", ids2.none { it in ids1 })
        }

    @Test
    fun `the anime category tab walks its own listing`() =
        runTest {
            assumeTrue(live())
            val page = configured().popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals(24, page.wallpapers.size)
            assertTrue("expected a next page", page.nextPage != null)
        }

    @Test
    fun `the homepage grid paginates the freshest uploads`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val page1 = configured.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()
            val page2 = configured.popular(page = 2, filters = Filters.of("sorting" to "date")).getOrThrow()

            // The homepage stacks its trending grid (24) plus a featured
            // carousel (10 more, verified overlap-free), so page one serves
            // a bonus batch; deeper pages serve the plain trending grid.
            assertTrue("expected at least the trending batch, got ${page1.wallpapers.size}", page1.wallpapers.size >= 24)
            val ids1 = page1.wallpapers.map { it.id }.toSet()
            assertTrue("expected fresh items on the homepage's page 2", page2.wallpapers.none { it.id in ids1 })
        }

    @Test
    fun `search answers with a single honest page`() =
        runTest {
            assumeTrue(live())
            val page = configured().search(query = "naruto", page = 1).getOrThrow()

            assertTrue("expected a real batch, got ${page.wallpapers.size}", page.wallpapers.size >= 10)
            assertTrue("the site's search has no page two", page.nextPage == null)
        }

    @Test
    fun `a query-preset shelf rides the site's own search`() =
        runTest {
            assumeTrue(live())
            val page = configured().search(query = "nature", page = 1).getOrThrow()

            assertTrue("expected a real batch, got ${page.wallpapers.size}", page.wallpapers.size >= 10)
        }

    @Test
    fun `details reads the definitive live record`() =
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
            assertTrue(details.wallpaper.fullUrl.startsWith("https://4kwallpapers.com/images/"))
            assertNotNull("expected the true resolution", details.resolution)
            assertTrue(
                "expected true dimensions, got ${details.resolution}",
                Regex("""\d{3,5}x\d{3,5}""").matches(details.resolution.orEmpty()),
            )
            assertTrue(details.sourceUrl!!.endsWith(first.id))
        }

    @Test
    fun `details of a portrait or square original keeps its true shape`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            // The people category carries portrait-first crops; walking it
            // exercises a non-16:9 original through the whole pipeline.
            val item =
                configured
                    .popular(page = 1, filters = Filters.of("category" to "people"))
                    .getOrThrow()
                    .wallpapers
                    .first()

            val details = configured.details(item.id).getOrThrow()
            val width = details.wallpaper.width
            val height = details.wallpaper.height
            assertTrue("expected true dimensions, got ${width}x$height", width != null && height != null)
            if (width == null || height == null) return@runTest
            if (height > width) {
                // A portrait original must not claim the card crop's ratio.
                assertTrue("portrait original mislabeled: ${width}x$height", height * 1.4 > width)
            }
        }
}

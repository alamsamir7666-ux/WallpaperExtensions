package com.cloudimage.alphacoders

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
 * Live check against alphacoders.com, for maintenance: the fixture tests
 * pin the markup shapes, this answers whether the SITE still speaks them.
 *
 * Run on demand with:
 *
 * `ALPHACODERS_LIVE=1 ./gradlew :providers:alphacoders:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 */
class AlphaCodersLiveCheckTest {
    private val provider = AlphaCodersWallpaperProvider()

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

    private fun live(): Boolean = System.getenv("ALPHACODERS_LIVE") == "1"

    private fun configured(): AlphaCodersWallpaperProvider = provider.apply { configure(LiveClient(), ProviderSettings { null }) }

    @Test
    fun `popular feed answers with real items and a next page`() =
        runTest {
            assumeTrue(live())
            val page = configured().popular(page = 1).getOrThrow()

            assertEquals("the site serves fifteen per page", 15, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertTrue("unexpected id shape: ${first.id}", Regex("""^\d+$""").matches(first.id))
            assertTrue(first.thumbUrl.startsWith("https://images"))
            assertTrue(first.thumbUrl.endsWith(".webp"))
            assertTrue(first.fullUrl.startsWith("https://images"))
            assertTrue(
                "originals are plain image files",
                Regex("""\.(:?jpe?g|png)$""").containsMatchIn(first.fullUrl),
            )
            assertNotNull(first.title)
            // Regression guard for the card-crop trap: the live listing
            // still publishes only its uniform 350x219 attrs, so the grid
            // must carry no dimensions — the true ones are details()'s alone.
            assertTrue(
                "grid items must not publish the card-crop dims (${first.width}x${first.height})",
                first.width == null && first.height == null,
            )
            assertEquals(2, page.nextPage)
        }

    @Test
    fun `page two serves a fresh batch`() =
        runTest {
            assumeTrue(live())
            val provider = configured()
            val pageOne = provider.popular(page = 1).getOrThrow()
            val pageTwo = provider.popular(page = 2).getOrThrow()

            assertEquals(15, pageTwo.wallpapers.size)
            val firstBatch = pageOne.wallpapers.map { it.id }.toSet()
            assertTrue(
                "page two repeated page one's batch",
                pageTwo.wallpapers.none { it.id in firstBatch },
            )
        }

    @Test
    fun `the newest feed and the anime topic answer`() =
        runTest {
            assumeTrue(live())
            val provider = configured()
            val newest =
                provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()
            val anime =
                provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals(15, newest.wallpapers.size)
            assertEquals(15, anime.wallpapers.size)
            assertEquals(2, anime.nextPage)
        }

    @Test
    fun `a search that names a topic hits and a garbage slug misses honestly`() =
        runTest {
            assumeTrue(live())
            val provider = configured()
            val hit = provider.search(query = "naruto", page = 1).getOrThrow()
            val miss = provider.search(query = "zzqwxywhatever", page = 1).getOrThrow()

            assertTrue("expected a naruto batch", hit.wallpapers.isNotEmpty())
            assertTrue(
                "expected an honest miss",
                miss.wallpapers.isEmpty() && miss.nextPage == null,
            )
        }

    @Test
    fun `details round-trips the true record`() =
        runTest {
            assumeTrue(live())
            val first =
                configured()
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()

            val details = configured().details(first.id).getOrThrow()

            assertEquals(first.id, details.wallpaper.id)
            assertNotNull("details must publish true dimensions", details.wallpaper.width)
            assertNotNull(details.wallpaper.height)
            assertEquals("${details.wallpaper.width}x${details.wallpaper.height}", details.resolution)
            assertNotNull("details must publish the file size", details.fileSizeBytes)
            assertTrue(details.fileSizeBytes!! > 10_000)
            assertEquals("https://wall.alphacoders.com/big.php?i=${first.id}", details.sourceUrl)
            assertTrue(details.wallpaper.tags.isNotEmpty())
        }

    @Test
    fun `the disclosed original and thumbnail really serve`() =
        runTest {
            assumeTrue(live())
            val first =
                configured()
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()

            // Two image fetches, exactly what rendering and downloading the
            // item cost.
            val original = LiveClient().get(first.fullUrl)
            val thumb = LiveClient().get(first.thumbUrl)

            assertEquals(200, original.statusCode)
            assertTrue(
                "original must be an image (${original.header("Content-Type")})",
                original.header("Content-Type")?.startsWith("image/") == true,
            )
            assertEquals(200, thumb.statusCode)
            assertTrue(thumb.header("Content-Type")?.startsWith("image/") == true)
            assertTrue(
                "the grid thumbnail must be far lighter than the original",
                thumb.body.size * 10 < original.body.size,
            )
        }

    @Test
    fun `a PNG original keeps its extension end to end`() =
        runTest {
            assumeTrue(live())
            val batch =
                configured()
                    .popular(page = 1, filters = Filters.of("category" to "anime"))
                    .getOrThrow()
                    .wallpapers

            val png = batch.firstOrNull { it.fullUrl.endsWith(".png") }
            assumeTrue("no PNG item in the first anime batch — nothing to guard", png != null)

            val details = configured().details(png!!.id).getOrThrow()

            assertTrue(details.wallpaper.fullUrl.endsWith(".png"))
            assertNotNull(details.wallpaper.width)
        }
}

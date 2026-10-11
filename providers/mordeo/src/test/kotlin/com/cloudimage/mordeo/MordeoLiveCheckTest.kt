package com.cloudimage.mordeo

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live check against mordeo.org, for maintenance: the fixture tests pin
 * the markup shapes, this answers whether the SITE still speaks them.
 *
 * Run on demand with:
 *
 * `MORDEO_LIVE=1 ./gradlew :providers:mordeo:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 */
class MordeoLiveCheckTest {
    private val provider = MordeoWallpaperProvider()

    /**
     * The plugin facade over HttpURLConnection, sending the app's own
     * User-Agent — the same identity [com.cloudimage.core.network] sends.
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
            connection.disconnect()
            return ProviderHttpResponse(status, emptyMap(), body)
        }
    }

    private fun live(): Boolean = System.getenv("MORDEO_LIVE") == "1"

    private suspend fun configureLive() {
        provider.configure(LiveClient(), ProviderSettings { null })
    }

    @Test
    fun theStreamStillServesItsCards() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page = provider.popular(page = 1).getOrThrow()

            assertTrue("stream empty", page.wallpapers.isNotEmpty())
            assertTrue("stream shallow: ${page.wallpapers.size}", page.wallpapers.size >= 5)
            assertEquals(2, page.nextPage)
            page.wallpapers.take(5).forEach { wallpaper ->
                assertTrue(wallpaper.id.isNotBlank())
                assertTrue(wallpaper.thumbUrl.startsWith("https://www.mordeo.org/files/uploads/"))
                assertTrue(wallpaper.fullUrl.startsWith("https://www.mordeo.org/files/uploads/"))
                assertTrue(wallpaper.fullUrl.endsWith(".jpg"))
            }
        }

    @Test
    fun theDeepStreamStillServesItsOldEraCards() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page = provider.popular(page = 60).getOrThrow()

            assertTrue("deep stream empty", page.wallpapers.isNotEmpty())
        }

    @Test
    fun theCategoryPagesStillServeTheirGrids() =
        runTest {
            assumeTrue(live())
            configureLive()

            val anime = provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            val people = provider.popular(page = 1, filters = Filters.of("category" to "people")).getOrThrow()

            assertTrue("anime category empty", anime.wallpapers.isNotEmpty())
            assertTrue("people category empty", people.wallpapers.isNotEmpty())
            assertEquals(2, anime.nextPage)
        }

    @Test
    fun searchStillAnswersAndTheEmptyPageStaysEmpty() =
        runTest {
            assumeTrue(live())
            configureLive()

            val hit = provider.search("moana", page = 1).getOrThrow()
            val miss = provider.search("zzzqqqxxx", page = 1).getOrThrow()

            assertTrue("search hit empty", hit.wallpapers.isNotEmpty())
            assertTrue("search miss not empty", miss.wallpapers.isEmpty())
            assertNull(miss.nextPage)
        }

    @Test
    fun theDerivedOriginalsStillResolve() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page = provider.popular(page = 1).getOrThrow()

            page.wallpapers.take(3).forEach { wallpaper ->
                val original = URL(wallpaper.fullUrl).openConnection() as HttpURLConnection
                original.setRequestProperty(
                    "User-Agent",
                    "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)",
                )
                val status = original.responseCode
                val type = original.contentType
                original.disconnect()
                assertEquals("original ${wallpaper.fullUrl} answered $status", 200, status)
                assertTrue("original ${wallpaper.fullUrl} served $type", type.startsWith("image/"))
            }
        }

    @Test
    fun detailsStillReadTheTrueOriginalAndDimensions() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page = provider.popular(page = 1).getOrThrow()
            val details = provider.details(page.wallpapers.first().id).getOrThrow()

            assertTrue(!details.wallpaper.title.isNullOrBlank())
            assertEquals("2160x3840", details.resolution)
            assertTrue(details.wallpaper.fullUrl.endsWith(".jpg"))
            assertTrue(details.sourceUrl!!.startsWith("https://www.mordeo.org/wallpapers/"))

            val original = URL(details.wallpaper.fullUrl).openConnection() as HttpURLConnection
            original.setRequestProperty(
                "User-Agent",
                "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)",
            )
            val status = original.responseCode
            val type = original.contentType
            original.disconnect()
            assertEquals(200, status)
            assertTrue("original served $type", type.startsWith("image/"))
        }
}

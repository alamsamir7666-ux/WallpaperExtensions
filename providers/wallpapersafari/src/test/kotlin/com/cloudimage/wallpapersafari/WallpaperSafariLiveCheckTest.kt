package com.cloudimage.wallpapersafari

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live check against wallpapersafari.com, for maintenance: the fixture
 * tests pin the markup shapes, this answers whether the SITE still
 * speaks them.
 *
 * Run on demand with:
 *
 * `WALLPAPERSAFARI_LIVE=1 ./gradlew :providers:wallpapersafari:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 */
class WallpaperSafariLiveCheckTest {
    private val provider = WallpaperSafariWallpaperProvider()

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

    private fun live(): Boolean = System.getenv("WALLPAPERSAFARI_LIVE") == "1"

    private fun configured(): WallpaperSafariWallpaperProvider = provider.apply { configure(LiveClient(), ProviderSettings { null }) }

    @Test
    fun `the home wall answers with a full batch and true dimensions`() =
        runTest {
            assumeTrue(live())
            val page = configured().popular(page = 1).getOrThrow()

            assertTrue("expected a substantial wall, got ${page.wallpapers.size}", page.wallpapers.size >= 40)
            val first = page.wallpapers.first()
            assertTrue(first.id.matches(Regex("[A-Za-z0-9]+")))
            assertTrue(first.thumbUrl.startsWith("https://mcdn.wallpapersafari.com/medium/"))
            assertTrue(first.fullUrl.startsWith("https://cdn.wallpapersafari.com/"))
            assertTrue(
                "the like widget's dimensions are the file's TRUE ones",
                run {
                    val w = first.width
                    val h = first.height
                    w != null && h != null && w >= 640
                },
            )
            assertTrue("the site's popular wall is one honest page", page.nextPage == null)
        }

    @Test
    fun `search matching one gallery serves it whole`() =
        runTest {
            assumeTrue(live())
            val page = configured().search(query = "indian actress", page = 1).getOrThrow()

            assertTrue(
                "expected the site's indian actress gallery whole, got ${page.wallpapers.size}",
                page.wallpapers.size >= 40,
            )
            assertTrue(page.wallpapers.all { it.fullUrl.startsWith("https://cdn.wallpapersafari.com/") })
            assertTrue(page.wallpapers.any { "indian actress" in it.tags })
        }

    @Test
    fun `search with many galleries walks fresh pages`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val page1 = configured.search(query = "anime", page = 1).getOrThrow()
            val page2 = configured.search(query = "anime", page = 2).getOrThrow()

            assertTrue("expected a full batch, got ${page1.wallpapers.size}", page1.wallpapers.size >= 10)
            val ids1 = page1.wallpapers.map { it.id }.toSet()
            assertTrue("expected fresh items on page 2", page2.wallpapers.none { it.id in ids1 })
        }

    @Test
    fun `a zero-result query is honestly empty`() =
        runTest {
            assumeTrue(live())
            val page = configured().search(query = "zzxxqwertynothing", page = 1).getOrThrow()

            assertTrue("trending suggestions must never serve as results", page.wallpapers.isEmpty())
            assertTrue(page.nextPage == null)
        }

    @Test
    fun `the anime category walks its directory`() =
        runTest {
            assumeTrue(live())
            val page = configured().popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()

            assertTrue("expected a merged batch, got ${page.wallpapers.size}", page.wallpapers.size >= 10)
            assertTrue("the directory is deep enough to offer more", page.nextPage != null)
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
            assertTrue(details.wallpaper.fullUrl.startsWith("https://cdn.wallpapersafari.com/"))
            val dims = "${details.wallpaper.width}x${details.wallpaper.height}"
            assertTrue("expected true dimensions, got $dims", details.wallpaper.width != null && details.wallpaper.height != null)
            assertTrue(details.sourceUrl!!.endsWith(first.id))
        }

    @Test
    fun `the disclosed original serves as a real image`() {
        assumeTrue(live())
        val connection = URL("https://cdn.wallpapersafari.com/79/73/TvuM20.jpg").openConnection() as HttpURLConnection
        connection.setRequestProperty(
            "User-Agent",
            "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)",
        )
        val status = connection.responseCode
        val type = connection.contentType
        val bytes = connection.inputStream.use { it.readBytes().size }
        connection.disconnect()

        assertEquals(200, status)
        assertTrue("expected an image, got $type", type.startsWith("image/"))
        assertTrue("expected the multi-resolution original, got ${bytes}B", bytes > 50_000)
    }
}

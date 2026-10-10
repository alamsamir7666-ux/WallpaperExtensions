package com.cloudimage.hdwallpapers

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
 * Live check against hdwallpapers.in, for maintenance: the fixture
 * tests pin the markup shapes, this answers whether the SITE still
 * speaks them.
 *
 * Run on demand with:
 *
 * `HDWALLPAPERS_LIVE=1 ./gradlew :providers:hdwallpapers:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 */
class HdWallpapersLiveCheckTest {
    private val provider = HdWallpapersWallpaperProvider()

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

    private fun live(): Boolean = System.getenv("HDWALLPAPERS_LIVE") == "1"

    private suspend fun configureLive() {
        provider.configure(LiveClient(), ProviderSettings { null })
    }

    @Test
    fun theLatestFeedStillServesItsGrid() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page = provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()

            assertTrue("latest feed empty", page.wallpapers.isNotEmpty())
            assertTrue("latest feed shallow: ${page.wallpapers.size}", page.wallpapers.size >= 15)
            assertEquals(2, page.nextPage)
            page.wallpapers.take(5).forEach { wallpaper ->
                assertTrue(wallpaper.id.isNotBlank())
                assertTrue(wallpaper.thumbUrl.startsWith("https://www.hdwallpapers.in/thumbs/"))
                assertTrue(wallpaper.thumbUrl.endsWith("-t2.jpg"))
                assertTrue(wallpaper.fullUrl.startsWith("https://www.hdwallpapers.in/download/"))
                assertTrue(wallpaper.fullUrl.endsWith("-HD.jpg"))
            }
        }

    @Test
    fun theRankedFeedsStillServeTheirGrids() =
        runTest {
            assumeTrue(live())
            configureLive()

            val popular = provider.popular(page = 1).getOrThrow()
            val toplist =
                provider
                    .popular(page = 1, filters = Filters.of("sorting" to "toplist"))
                    .getOrThrow()

            assertTrue("most viewed empty", popular.wallpapers.isNotEmpty())
            assertTrue("most downloaded empty", toplist.wallpapers.isNotEmpty())
        }

    @Test
    fun aCategoryPageStillServesItsGrid() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page =
                provider
                    .popular(page = 1, filters = Filters.of("category" to "anime"))
                    .getOrThrow()

            // The anime category grid, twenty cells, continuing.
            assertTrue("anime category thin: ${page.wallpapers.size}", page.wallpapers.size >= 15)
            assertEquals(2, page.nextPage)
        }

    @Test
    fun searchStillAnswersAndTheApologyStillMarksZeroResults() =
        runTest {
            assumeTrue(live())
            configureLive()

            val found = provider.search("leopard", page = 1).getOrThrow()
            assertTrue("leopard answered nothing", found.wallpapers.isNotEmpty())
            assertEquals(2, found.nextPage)

            val none = provider.search("zzxxqqwertynope", page = 1).getOrThrow()
            assertTrue("the zero-result apology was not honored", none.wallpapers.isEmpty())
        }

    @Test
    fun searchStillPaginatesAsDeepAsTheSiteGoes() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page2 = provider.search("leopard", page = 2).getOrThrow()

            assertTrue("search page 2 empty", page2.wallpapers.isNotEmpty())
        }

    @Test
    fun detailsStillServeTheTrueResolution() =
        runTest {
            assumeTrue(live())
            configureLive()

            val latest =
                provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()
            val first = latest.wallpapers.first()

            val details = provider.details(first.id).getOrThrow()

            assertEquals(first.id, details.wallpaper.id)
            assertTrue(details.resolution!!.matches(Regex("""\d+x\d+""")))
            assertEquals(
                "https://www.hdwallpapers.in/download/${first.id}-HD.jpg",
                details.wallpaper.fullUrl,
            )
            assertTrue(details.wallpaper.width!! > 0)
            assertTrue(details.wallpaper.height!! > 0)
        }
}

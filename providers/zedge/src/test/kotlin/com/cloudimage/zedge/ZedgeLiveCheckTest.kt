package com.cloudimage.zedge

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
 * Live check against zedge.net, for maintenance: the fixture tests
 * pin the grammar this reads, this answers whether the SITE still
 * speaks it — whether the flight stream still carries the listing
 * `initialData` item records, whether the search sections still
 * scope the wallpapers, whether the detail pages still state their
 * `shareUrl`-bearing records.
 *
 * Run on demand with:
 *
 * `ZEDGE_LIVE=1 ./gradlew :providers:zedge:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set —
 * CI and plain `check` never touch the network. Requests are
 * sequential and few, exactly what one user browsing the app
 * produces.
 *
 * The zone runs a Cloudflare managed challenge over datacenter
 * egress — from a challenged network these checks fail with the
 * honest HTTP 403 failure the provider reports (they pass from the
 * residential-egress networks the app's own users ride, where the
 * host ladder never even needs its WebView lane). That makes them
 * the maintenance instrument for the day the site reshapes: run
 * them from a clean network, and the assertion that breaks names
 * the shape that drifted.
 */
class ZedgeLiveCheckTest {
    private val provider = ZedgeWallpaperProvider()

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

    private fun live(): Boolean = System.getenv("ZEDGE_LIVE") == "1"

    private suspend fun configureLive() {
        provider.configure(LiveClient(), ProviderSettings { null })
    }

    /** The signed rendition host every zedge image speaks. */
    private val renditionHost = "https://is.zobj.net/image-server/v1/images?r="

    @Test
    fun theLandingStillServesItsTiles() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page = provider.popular(page = 1).getOrThrow()

            assertTrue("landing empty", page.wallpapers.isNotEmpty())
            assertTrue("landing shallow: ${page.wallpapers.size}", page.wallpapers.size >= 5)
            assertEquals("the landing hides its continuation", 2, page.nextPage)
            page.wallpapers.take(5).forEach { wallpaper ->
                assertTrue("id not the UUID route id: ${wallpaper.id}", wallpaper.id.length == 36)
                assertTrue("thumb not the site's rendition: ${wallpaper.thumbUrl}", wallpaper.thumbUrl.startsWith(renditionHost))
                assertTrue("full not the site's rendition: ${wallpaper.fullUrl}", wallpaper.fullUrl.startsWith(renditionHost))
            }
        }

    @Test
    fun theLandingStillStatesItsContinuationCursor() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals("page 1 must state its cursor to keep the walk alive", 2, page.nextPage)

            // THE grammar probe: a clean network asks the server for the
            // cursor page 1 handed over. Either the walk advances — fresh
            // tiles — or the server ignores the cursor and the guards
            // answer the honest empty. A REPEAT of page 1's tiles is the
            // one outcome this must never produce: if it fails here, the
            // continuation grammar (or the guards) drifted.
            val second = provider.popular(page = 2).getOrThrow()

            if (second.wallpapers.isNotEmpty()) {
                val firstIds = page.wallpapers.map { it.id }.toSet()
                second.wallpapers.forEach { wallpaper ->
                    assertTrue("page 2 repeated a tile: ${wallpaper.id}", wallpaper.id !in firstIds)
                }
            }
            assertTrue(
                "the walk must either advance or end honestly — never dangle",
                second.nextPage == null || second.wallpapers.isNotEmpty(),
            )
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
        }

    @Test
    fun searchStillAnswersTheWallpapersOnlyGrid() =
        runTest {
            assumeTrue(live())
            configureLive()

            val hit = provider.search("wolf", page = 1).getOrThrow()

            assertTrue("search hit empty", hit.wallpapers.isNotEmpty())
            assertTrue(
                "the wallpapers-only route serves a full grid: ${hit.wallpapers.size}",
                hit.wallpapers.size >= 12,
            )
            hit.wallpapers.forEach { wallpaper ->
                assertTrue("search served a non-wallpaper shape: ${wallpaper.thumbUrl}", wallpaper.thumbUrl.startsWith(renditionHost))
            }

            // THE search grammar probe — the same walk the landing rides:
            // a deeper page either advances with fresh tiles or ends
            // honestly, and NEVER repeats a tile already served.
            if (hit.nextPage != null) {
                val second = provider.search("wolf", page = 2).getOrThrow()
                if (second.wallpapers.isNotEmpty()) {
                    val firstIds = hit.wallpapers.map { it.id }.toSet()
                    second.wallpapers.forEach { wallpaper ->
                        assertTrue("search page 2 repeated a tile: ${wallpaper.id}", wallpaper.id !in firstIds)
                    }
                }
                assertTrue(
                    "the search walk must advance or end honestly — never dangle",
                    second.nextPage == null || second.wallpapers.isNotEmpty(),
                )
            }
        }

    @Test
    fun detailsStillReadTheDefinitiveRecord() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page = provider.popular(page = 1).getOrThrow()
            val details = provider.details(page.wallpapers.first().id).getOrThrow()

            assertTrue(!details.wallpaper.title.isNullOrBlank())
            assertTrue(details.wallpaper.fullUrl.startsWith(renditionHost))
            assertTrue(details.sourceUrl!!.startsWith("https://www.zedge.net/wallpaper/"))
        }
}

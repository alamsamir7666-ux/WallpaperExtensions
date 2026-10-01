package com.cloudimage.wallpaperaccess

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
 * Live check against wallpaperaccess.com, for maintenance: the fixture
 * tests pin the markup shapes, this answers whether the SITE still
 * speaks them.
 *
 * Run on demand with:
 *
 * `WALLPAPERACCESS_LIVE=1 ./gradlew :providers:wallpaperaccess:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces; the site sits behind
 * Cloudflare, so the checks stay paced.
 */
class WallpaperAccessLiveCheckTest {
    private val provider = WallpaperAccessWallpaperProvider()

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

    private fun live(): Boolean = System.getenv("WALLPAPERACCESS_LIVE") == "1"

    private suspend fun configureLive() {
        provider.configure(LiveClient(), ProviderSettings { null })
    }

    @Test
    fun theNavigationStillCarriesTheCategories() =
        runTest {
            assumeTrue(live())
            configureLive()

            val categories = provider.categories()

            // The baked floor is 28; the live menu must answer at least
            // that many of the same slugs, emoji first.
            assertTrue("live categories too thin: ${categories.size}", categories.size >= 28)
            val anime = categories.first { it.id == "anime" }
            assertEquals("Anime", anime.name)
            assertTrue(anime.iconEmoji != null)
        }

    @Test
    fun theHomepageShelfStillServesAlbumCards() =
        runTest {
            assumeTrue(live())
            configureLive()

            val albums = provider.homeAlbums().getOrThrow()

            assertTrue("homepage shelf empty", albums.isNotEmpty())
            albums.take(5).forEach { album ->
                assertTrue(album.id.isNotBlank())
                assertTrue(album.title.isNotBlank())
                assertTrue(album.coverUrl.startsWith("https://wallpaperaccess.com/thumb/"))
            }
        }

    @Test
    fun aCategoryPageStillServesItsAlbumDirectoryComplete() =
        runTest {
            assumeTrue(live())
            configureLive()

            val albums = provider.albums("anime").getOrThrow()

            // The anime directory served 150 cards at capture time.
            assertTrue("anime directory thin: ${albums.size}", albums.size >= 100)
        }

    @Test
    fun anAlbumPageStillServesItsCompleteWallWithTrueDimensions() =
        runTest {
            assumeTrue(live())
            configureLive()

            // Attack On Titan: 70 walls at capture time, mixed JPG and PNG.
            val wallpapers = provider.albumWallpapers("attack-on-titan").getOrThrow()

            assertTrue("album wall thin: ${wallpapers.size}", wallpapers.size >= 60)
            assertTrue(wallpapers.all { it.fullUrl.startsWith("https://wallpaperaccess.com/full/") })
            assertTrue(wallpapers.all { it.thumbUrl.startsWith("https://wallpaperaccess.com/thumb/") })
            assertTrue(wallpapers.any { it.fullUrl.endsWith(".png") })
            assertTrue(wallpapers.all { it.width != null && it.height != null })
        }

    @Test
    fun searchStillAnswersAlbumsAndTheApologyStillMarksZeroResults() =
        runTest {
            assumeTrue(live())
            configureLive()

            val found = provider.searchAlbums("naruto").getOrThrow()
            assertTrue("naruto answered no albums", found.isNotEmpty())
            assertTrue(found.all { it.wallpaperCount > 0 })

            val none = provider.searchAlbums("zzxxqqwertynope").getOrThrow()
            assertTrue("the zero-result apology was not honored", none.isEmpty())
        }

    @Test
    fun detailsStillReReadTheAlbumRecord() =
        runTest {
            assumeTrue(live())
            configureLive()

            val wallpapers = provider.albumWallpapers("attack-on-titan").getOrThrow()
            val first = wallpapers.first()

            val details = provider.details(first.id).getOrThrow()

            assertEquals(first.id, details.wallpaper.id)
            assertTrue(details.resolution!!.matches(Regex("""\d+x\d+""")))
            val wallpaperId = first.id.substringAfter('/').substringBefore('.')
            assertEquals("https://wallpaperaccess.com/attack-on-titan#$wallpaperId", details.sourceUrl)
        }
}
